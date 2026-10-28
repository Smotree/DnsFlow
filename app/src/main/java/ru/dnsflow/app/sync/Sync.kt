package ru.dnsflow.app.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import ru.dnsflow.app.App
import ru.dnsflow.app.data.LookupStatus
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Product
import ru.dnsflow.app.data.Repo
import ru.dnsflow.app.data.Session
import ru.dnsflow.app.sync.Protocol.str

/**
 * Live shared acceptance between phones standing next to each other, over Google Nearby
 * Connections (Bluetooth + Wi‑Fi, no internet or router needed). One phone hosts and shows an
 * invite QR; the others scan it and join. Every scan or edit is sent right away, the host
 * relays it to the rest, and on every (re)connect the full lists are exchanged both ways, so
 * whatever was done while out of range is caught up. Each phone always keeps its own full copy.
 * The shared mode is remembered per acceptance and resumes after an app restart.
 */
object Sync {
    private const val TAG = "Sync"
    private const val SERVICE_ID = "ru.dnsflow.app.acceptance"
    private const val INVITE_PREFIX = "DNSFLOW-JOIN|"
    private const val ROLE_HOST = 1
    private const val ROLE_GUEST = 2

    enum class Role { NONE, HOST, GUEST }
    data class Peer(val id: String, val name: String)
    data class State(
        val role: Role = Role.NONE,
        val sessionId: Long = 0,
        val uid: String = "",
        val hostName: String = "",
        val peers: List<Peer> = emptyList(),
        val searching: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val app get() = App.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val names = mutableMapOf<String, String>()
    private val strategy = Strategy.P2P_STAR
    private var watchdog: Job? = null

    val permissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            // Nearby still asks for location on many phones and Android versions when searching
            // (error 8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION), so it is requested everywhere.
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

    /** Searching for nearby phones needs the phone's location switch on, too. */
    fun locationOn(): Boolean = app.getSystemService(android.location.LocationManager::class.java)
        ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: true

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun myName() = app.prefs.deviceName
    private fun tag(uid: String) = uid.take(8)
    private fun endpointName(uid: String) = tag(uid) + "|" + myName()
    private fun displayName(endpointName: String) = endpointName.substringAfter('|')

    fun inviteText(s: State) = INVITE_PREFIX + s.uid + "|" + s.hostName

    fun isInvite(raw: String) = raw.startsWith(INVITE_PREFIX)

    /**
     * Restores the shared mode of [session] after an app restart. Missing permissions show up
     * as an error in the banner (tap = ask again) instead of silently staying offline.
     */
    fun resume(session: Session) {
        if (session.syncRole == 0 || _state.value.role != Role.NONE) return
        if (session.syncRole == ROLE_HOST) startHost(session) else startGuest(session.id, session.uid, session.syncHost)
    }

    /** Tries again after the user granted permissions or turned location on. */
    fun retry() {
        val s = _state.value
        when (s.role) {
            Role.GUEST -> discover()
            Role.HOST -> scope.launch { app.dao.session(s.sessionId)?.let { startHost(it) } }
            Role.NONE -> Unit
        }
    }

    // ---- Host ----

    fun host(sessionId: Long) {
        scope.launch {
            val session = ensureUid(sessionId) ?: return@launch
            if (_state.value.role == Role.HOST && _state.value.sessionId == sessionId) return@launch
            remember(session.copy(syncRole = ROLE_HOST, syncHost = ""))
            startHost(session)
        }
    }

    private fun startHost(session: Session) {
        disconnectAll()
        _state.value = State(Role.HOST, session.id, session.uid, myName())
        if (!hasPermissions()) {
            _state.update { it.copy(error = "нет разрешений «Устройства поблизости» и «Местоположение» — нажмите здесь") }
            return
        }
        client.startAdvertising(
            endpointName(session.uid), SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { fail("Не удалось включить приём подключений", it) }
    }

    // ---- Guest ----

    /**
     * Joins the acceptance from an invite QR and returns the local session for it. With
     * [fromSessionId] the places already scanned here are brought into the shared acceptance.
     */
    suspend fun join(invite: String, fromSessionId: Long? = null): Long? {
        val parts = invite.removePrefix(INVITE_PREFIX).split('|', limit = 2)
        val uid = parts.getOrNull(0)?.takeIf { it.length >= 8 } ?: return null
        val hostName = parts.getOrNull(1).orEmpty()
        val dao = app.dao
        val existing = dao.sessionByUid(uid)
        val from = fromSessionId?.let { dao.session(it) }
        val sessionId = when {
            existing != null && from != null && from.id != existing.id -> {
                // Both exist: move this acceptance's places into the shared one.
                Repo.applyRemote(existing.id, dao.placesWithDeleted(from.id))
                Repo.deleteSession(from.id)
                existing.id
            }
            existing != null -> existing.id
            from != null -> from.id.also { dao.updateSession(from.copy(uid = uid)) }
            else -> dao.insertSession(
                Session(title = "Совместная приёмка ($hostName)", createdAt = System.currentTimeMillis(), uid = uid)
            )
        }
        dao.session(sessionId)?.let { remember(it.copy(syncRole = ROLE_GUEST, syncHost = hostName)) }
        startGuest(sessionId, uid, hostName)
        return sessionId
    }

    private fun startGuest(sessionId: Long, uid: String, hostName: String) {
        disconnectAll()
        _state.value = State(Role.GUEST, sessionId, uid, hostName)
        discover()
        // Keep looking while the host is out of range; reconnects as soon as it is back.
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive && _state.value.role == Role.GUEST) {
                delay(20_000)
                if (_state.value.role == Role.GUEST && _state.value.peers.isEmpty()) discover()
            }
        }
    }

    private fun discover() {
        val s = _state.value
        if (s.role != Role.GUEST) return
        if (!hasPermissions()) {
            _state.update { it.copy(searching = false, error = "нет разрешений «Устройства поблизости» и «Местоположение» — нажмите здесь") }
            return
        }
        if (!locationOn()) {
            _state.update { it.copy(searching = false, error = "включите геолокацию в шторке телефона — без неё поиск не работает") }
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        client.stopDiscovery()
        client.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (!info.endpointName.startsWith(tag(s.uid) + "|")) return
                client.requestConnection(endpointName(s.uid), endpointId, lifecycle)
                    .addOnFailureListener { Log.w(TAG, "request failed", it) }
            }

            override fun onEndpointLost(endpointId: String) {}
        }, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail("Не удалось начать поиск телефона", it) }
    }

    /** Leaves the shared mode for good (the user pressed "disconnect"). */
    fun stop() {
        val id = _state.value.sessionId
        disconnectAll()
        if (id != 0L) scope.launch { app.dao.session(id)?.let { app.dao.updateSession(it.copy(syncRole = 0)) } }
    }

    private fun disconnectAll() {
        watchdog?.cancel()
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        names.clear()
        _state.value = State()
    }

    private suspend fun remember(session: Session) {
        app.dao.updateSession(session)
        app.dao.clearSyncRoleExcept(session.id)
    }

    // ---- Connection handling ----

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val s = _state.value
            if (s.role == Role.NONE || !info.endpointName.startsWith(tag(s.uid) + "|")) {
                client.rejectConnection(endpointId)
                return
            }
            names[endpointId] = displayName(info.endpointName)
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                names.remove(endpointId)
                if (_state.value.role == Role.GUEST) retryDiscovery()
                return
            }
            _state.update {
                it.copy(peers = it.peers.filter { p -> p.id != endpointId } + Peer(endpointId, names[endpointId] ?: "?"), searching = false)
            }
            if (_state.value.role == Role.GUEST) client.stopDiscovery()
            scope.launch { sendFullState(endpointId) }
        }

        override fun onDisconnected(endpointId: String) {
            names.remove(endpointId)
            _state.update { it.copy(peers = it.peers.filter { p -> p.id != endpointId }) }
            if (_state.value.role == Role.GUEST) retryDiscovery()
        }
    }

    private fun retryDiscovery() {
        scope.launch {
            delay(2000)
            if (_state.value.role == Role.GUEST && _state.value.peers.isEmpty()) discover()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch {
                runCatching { handle(endpointId, JSONObject(String(bytes, Charsets.UTF_8))) }
                    .onFailure { Log.w(TAG, "bad message", it) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private suspend fun handle(from: String, msg: JSONObject) {
        val s = _state.value
        if (msg.optString("uid") != s.uid) return
        when (msg.optString("t")) {
            "hello" -> if (s.role == Role.GUEST) {
                app.dao.session(s.sessionId)?.let { local ->
                    val title = msg.optString("title")
                    if (title.isNotBlank() && local.title != title) app.dao.updateSession(local.copy(title = title))
                }
            }
            "places" -> {
                val res = Repo.applyRemote(s.sessionId, Protocol.readPlaces(msg), renumber = false)
                if (res.reorder) scheduleRenumber(s.sessionId)
                // Places marked as a problem here go back to everyone, the sender included.
                if (res.conflicts.isNotEmpty()) send(res.conflicts)
                // The host relays what was new for it to the other phones.
                if (s.role == Role.HOST) {
                    val conflictIds = res.conflicts.map { it.id }.toSet()
                    send(res.changed.filter { it.id !in conflictIds }, except = from)
                }
            }
            "products" -> {
                val arr = msg.getJSONArray("items")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ean = o.getString("ean")
                    val local = app.dao.product(ean)
                    if (local == null || local.status != LookupStatus.FOUND) {
                        app.dao.upsertProduct(Product(ean, LookupStatus.FOUND, code = o.str("code"), name = o.str("name"), url = o.str("url")))
                    }
                }
            }
            "split" -> Repo.setMyPart(s.sessionId, msg.optInt("parts"), msg.optInt("part"))
        }
    }

    private var renumberJob: Job? = null

    /** Renumbers once the burst of incoming messages is over (a full exchange is many messages). */
    private fun scheduleRenumber(sessionId: Long) {
        renumberJob?.cancel()
        renumberJob = scope.launch {
            delay(400)
            Repo.renumber(sessionId)
        }
    }

    private suspend fun sendFullState(endpointId: String) {
        val s = _state.value
        val session = app.dao.session(s.sessionId) ?: return
        sendTo(listOf(endpointId), JSONObject().put("t", "hello").put("uid", s.uid).put("title", session.title))
        val places = app.dao.placesWithDeleted(s.sessionId)
        places.chunked(Protocol.CHUNK).forEach { sendTo(listOf(endpointId), Protocol.places(s.uid, it, myName())) }
        val eans = places.mapNotNull { it.ean }.toSet()
        val products = app.dao.productsOnce().filter { it.ean in eans && it.status == LookupStatus.FOUND }
        products.chunked(100).forEach { chunk ->
            sendTo(listOf(endpointId), JSONObject().put("t", "products").put("uid", s.uid).put("items", JSONArray().apply {
                chunk.forEach { put(JSONObject().put("ean", it.ean).put("code", it.code).put("name", it.name).put("url", it.url)) }
            }))
        }
    }

    /** Called by [Repo] after every local change. */
    fun publish(sessionId: Long, places: List<Place>) {
        val s = _state.value
        if (s.role == Role.NONE || s.sessionId != sessionId || places.isEmpty()) return
        send(places)
    }

    /** Host: splits the 1C check equally between itself and the connected phones. */
    fun splitBetweenPhones() {
        val s = _state.value
        if (s.role != Role.HOST) return
        app.scope.launch {
            val parts = s.peers.size + 1
            Repo.split(s.sessionId, parts)
            Repo.setMyPart(s.sessionId, parts, 1)
            s.peers.forEachIndexed { i, peer ->
                sendTo(listOf(peer.id), JSONObject().put("t", "split").put("uid", s.uid).put("parts", parts).put("part", i + 2))
            }
        }
    }

    private fun send(places: List<Place>, except: String? = null) {
        val s = _state.value
        val targets = s.peers.map { it.id }.filter { it != except }
        if (targets.isEmpty() || places.isEmpty()) return
        places.chunked(Protocol.CHUNK).forEach { sendTo(targets, Protocol.places(s.uid, it, myName())) }
    }

    private fun sendTo(endpoints: List<String>, msg: JSONObject) {
        if (endpoints.isEmpty()) return
        client.sendPayload(endpoints, Payload.fromBytes(msg.toString().toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { Log.w(TAG, "send failed", it) }
    }

    private suspend fun ensureUid(sessionId: Long): Session? {
        val s = app.dao.session(sessionId) ?: return null
        if (s.uid.isNotBlank()) return s
        return s.copy(uid = java.util.UUID.randomUUID().toString()).also { app.dao.updateSession(it) }
    }

    private fun fail(text: String, e: Exception) {
        Log.w(TAG, text, e)
        _state.update { it.copy(error = "$text: ${e.message}", searching = false) }
    }
}
