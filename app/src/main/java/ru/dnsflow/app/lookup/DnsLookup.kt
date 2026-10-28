package ru.dnsflow.app.lookup

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import ru.dnsflow.app.App
import ru.dnsflow.app.data.LookupStatus
import ru.dnsflow.app.data.Product
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * Finds products on dns-shop.ru by EAN. The site protects itself with a JS challenge,
 * so plain HTTP requests get an empty page; a hidden WebView passes it like a browser.
 * Searching by EAN redirects straight to the product card, whose title ends with
 * "| <product code>."
 */
class DnsLookup(private val app: App) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val queued = mutableSetOf<String>()
    private var webView: WebView? = null

    fun start() {
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (app.prefs.autoLookup) retryErrors()
                }
            }
        )
        scope.launch {
            // Lookups before 1.8 could save the previous product's page for a new EAN: check them all again once.
            if (!app.prefs.lookupRechecked) {
                app.dao.markFoundPending()
                app.prefs.lookupRechecked = true
            }
            if (app.prefs.autoLookup) app.dao.pendingProducts().forEach { push(it.ean) }
            for (ean in queue) {
                try {
                    process(ean)
                } catch (e: Exception) {
                    Log.w(TAG, "lookup $ean failed", e)
                    markError(ean)
                } finally {
                    queued.remove(ean)
                }
                delay(400) // gentle pace: the site blocks bursts of requests
            }
        }
    }

    /** Registers an EAN and, when auto lookup is on, queues a search for it. */
    fun request(ean: String, force: Boolean = false) {
        scope.launch {
            val existing = app.dao.product(ean)
            if (existing == null || force) {
                app.dao.upsertProduct(Product(ean, LookupStatus.PENDING))
            } else if (existing.status == LookupStatus.FOUND) {
                return@launch
            }
            if (app.prefs.autoLookup || force) push(ean)
        }
    }

    /** Re-queues everything that failed or was not found. */
    fun retryFailed() {
        scope.launch {
            app.dao.resetFailedProducts()
            app.dao.markFailedPending()
            app.dao.pendingProducts().forEach { push(it.ean) }
        }
    }

    /** Looks every known product up again (e.g. after a wrong match). */
    fun recheckAll() {
        scope.launch {
            app.dao.markFoundPending()
            app.dao.resetFailedProducts()
            app.dao.markFailedPending()
            app.dao.pendingProducts().forEach { push(it.ean) }
        }
    }

    /** Re-queues lookups that failed for network reasons. */
    fun retryErrors() {
        scope.launch {
            app.dao.pendingProducts().forEach { push(it.ean) }
        }
    }

    private fun push(ean: String) {
        if (queued.add(ean)) queue.trySend(ean)
    }

    /** Not on the site: also drops a code/name left from an earlier (wrong) match. */
    private suspend fun notFound(wv: WebView, ean: String) {
        val p = app.dao.product(ean) ?: Product(ean, LookupStatus.PENDING)
        app.dao.upsertProduct(
            p.copy(status = LookupStatus.NOT_FOUND, code = null, name = null, url = null, attempts = p.attempts + 1, updatedAt = now())
        )
        wv.loadUrl("about:blank")
    }

    private suspend fun markError(ean: String, countAttempt: Boolean = true) {
        val p = app.dao.product(ean) ?: Product(ean, LookupStatus.PENDING)
        val attempts = if (countAttempt) p.attempts + 1 else p.attempts
        app.dao.upsertProduct(p.copy(status = LookupStatus.ERROR, attempts = attempts, updatedAt = now()))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun web(): WebView = webView ?: WebView(app).also { wv ->
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // Only the title and the product code are read: skip pictures, fonts and other sites' scripts.
        wv.settings.blockNetworkImage = true
        wv.settings.loadsImagesAutomatically = false
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val host = url.host.orEmpty()
                val path = url.path.orEmpty().lowercase()
                val ownSite = host == "dns-shop.ru" || host.endsWith(".dns-shop.ru")
                return if (!ownSite || SKIPPED.containsMatchIn(path)) EMPTY() else null
            }
        }
        // A detached WebView has 0x0 size; give it a phone-sized layout so the page scripts run normally.
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, 1080, 1920)
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)
        webView = wv
    }

    private suspend fun process(ean: String) {
        val wv = web()
        // Mark the page that is open now. Until the search for this EAN really replaces it, the
        // script answers "stale" — reading it was what saved the previous product for a new EAN
        // when the network was slow.
        eval(wv, MARK_OLD)
        // The site keeps a 12-digit UPC-A as EAN-13 with a leading 0 and finds it only that way.
        val query = if (ean.length == 12 && ean.all { it.isDigit() }) "0$ean" else ean
        wv.loadUrl("https://www.dns-shop.ru/search/?q=$query")
        var followedList = false
        var triedLocalCode = false
        var productSeenAt = -1
        for (tick in 0 until TIMEOUT_TICKS) {
            delay(TICK_MS)
            val json = eval(wv, SCRIPT)
            if (json == null) continue
            if (json.optString("k") != "wait") Log.d(TAG, "$ean tick $tick -> $json")
            when (json.optString("k")) {
                "product" -> {
                    val name = json.optString("name").takeIf { it.isNotBlank() }
                    val code = json.optString("code").takeIf { it.isNotBlank() && it != "null" }
                    if (productSeenAt < 0) productSeenAt = tick
                    // The code is what matters; wait a few seconds for the name, then take what we have.
                    if ((code != null && name != null) || ((code != null || name != null) && (tick - productSeenAt) * TICK_MS >= 4000)) {
                        app.dao.upsertProduct(
                            Product(
                                ean = ean,
                                status = LookupStatus.FOUND,
                                code = code,
                                name = name,
                                url = json.optString("url"),
                                updatedAt = now(),
                            )
                        )
                        wv.loadUrl("about:blank")
                        return
                    }
                }
                // Search shows a list instead of opening the product. One result, or several cards of
                // the very same product under different DNS codes (e.g. Philips FC9734/01 = 5482008
                // and 1170003), is this EAN; different products are guesses, so the EAN is not found.
                "list" -> {
                    val items = json.optJSONArray("items")
                    val all = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }
                    val names = all.map { it.optString("name") }.distinct()
                    val codes = all.map { it.optString("code") }.filter { it.isNotBlank() }.distinct()
                    if (all.isNotEmpty() && names.size == 1 && names[0].isNotBlank() && codes.isNotEmpty()) {
                        app.dao.upsertProduct(
                            Product(
                                ean = ean,
                                status = LookupStatus.FOUND,
                                code = codes.joinToString(" / "),
                                name = names[0],
                                url = all[0].optString("url"),
                                updatedAt = now(),
                            )
                        )
                        wv.loadUrl("about:blank")
                        return
                    } else if (json.optInt("n") == 1 && !followedList) {
                        followedList = true
                        eval(wv, MARK_OLD)
                        wv.loadUrl(json.optString("url"))
                    } else if (json.optInt("n") > 1) {
                        notFound(wv, ean)
                        return
                    }
                }
                "neterr" -> {
                    // No internet / site unreachable: retried when the network comes back.
                    wv.loadUrl("about:blank")
                    markError(ean, countAttempt = false)
                    return
                }
                // DNS's own sticker ("локальник") is an EAN-8 of the 7-digit product code + check digit.
                "empty" -> if (ean.length == 8 && ean.all { it.isDigit() } && !triedLocalCode) {
                    triedLocalCode = true
                    eval(wv, MARK_OLD)
                    wv.loadUrl("https://www.dns-shop.ru/search/?q=${ean.take(7)}")
                } else {
                    notFound(wv, ean)
                    return
                }
            }
        }
        wv.loadUrl("about:blank")
        markError(ean)
    }

    /**
     * Runs [js] in the page. A navigation that commits while the script is queued drops its
     * callback for good (the search redirects to the product card), so give up after a while
     * instead of waiting forever.
     */
    private suspend fun eval(wv: WebView, js: String): JSONObject? = withTimeoutOrNull(2000) {
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                val obj = runCatching {
                    (JSONTokener(result).nextValue() as? String)?.let(::JSONObject)
                }.getOrNull()
                if (cont.isActive) cont.resume(obj)
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "DnsLookup"
        private const val TICK_MS = 250L
        private const val TIMEOUT_TICKS = 30_000 / TICK_MS.toInt()

        /** Pictures, fonts, video: not needed to read the product title and code. */
        private val SKIPPED = Regex("\\.(png|jpe?g|webp|gif|svg|ico|avif|woff2?|ttf|otf|eot|mp4|webm)$")

        private fun EMPTY() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        private const val MARK_OLD = "(function(){ window.__dnsflowOld = 1; return '{}'; })()"

        private val SCRIPT = """
            (function(){
              if (window.__dnsflowOld) return JSON.stringify({k:'wait'});
              var u = location.href;
              var body = document.body ? document.body.innerText : '';
              if (u.indexOf('/product/') >= 0) {
                var h1 = document.querySelector('h1');
                var m = document.title.match(/\|\s*(\d{4,9})\.?\s*$/) || body.match(/Код товара:?\s*(\d{4,9})/);
                // h1 fills in late on the mobile site; the title has the name right away.
                var tn = document.title.match(/^Купить\s+(.+?)\s+в интернет-магазине DNS/);
                var name = (h1 && h1.innerText.trim()) || (tn ? tn[1] : '');
                return JSON.stringify({k:'product', url:u, name: name,
                  code: m ? m[1] : null});
              }
              if (/ничего нет|ничего не найдено/i.test(body)) return JSON.stringify({k:'empty'});
              var cards = document.querySelectorAll('.catalog-product');
              var a = document.querySelector('a.catalog-product__name') || document.querySelector('.catalog-product a[href*="/product/"]');
              // Name without the "[2100 Вт, …]" specs; data-code is the DNS product code.
              var items = Array.prototype.map.call(cards, function(c){
                var l = c.querySelector('a.catalog-product__name') || c.querySelector('a[href*="/product/"]');
                var t = l ? l.textContent.replace(/\s*\[.*$/, '').replace(/\s+/g, ' ').trim() : '';
                return {code: c.getAttribute('data-code') || '', name: t, url: l ? l.href : ''};
              });
              if (a) return JSON.stringify({k:'list', url:a.href, n: Math.max(cards.length, 1), items: items});
              if (/net::ERR_/.test(body)) return JSON.stringify({k:'neterr', b:body.slice(-80)});
              return JSON.stringify({k:'wait', t:document.title});
            })()
        """.trimIndent()
    }
}
