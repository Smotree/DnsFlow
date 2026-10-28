package ru.dnsflow.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dnsflow.app.App
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Repo

/**
 * Shows the scanned codes one by one, regenerated from the stored text, so they can be
 * scanned from the phone screen into 1C. [sessionId] 0 means all open problem places.
 */
@Composable
fun ReviewScreen(sessionId: Long, startPlaceId: Long, part: Int, onBack: () -> Unit, openProblems: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val problemsMode = sessionId == 0L
    val livePlaces by remember {
        if (problemsMode) app.dao.openProblemPlaces() else app.dao.places(sessionId)
    }.collectAsState(initial = null)
    val products by remember { app.dao.products() }.collectAsState(initial = emptyList())
    val productMap = remember(products) { products.associateBy { it.ean } }

    // -1 = this phone's part of a split check, 0 = everything, k = part k.
    var activePart by remember { mutableStateOf<Int?>(if (part >= 0 || problemsMode) maxOf(part, 0) else null) }
    var partsTotal by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val s = if (problemsMode) null else app.dao.session(sessionId)
        partsTotal = s?.splitParts ?: 0
        if (activePart == null) activePart = if (s != null && s.splitParts > 1) s.myPart else 0
    }

    // The order is frozen on first load so marking/resolving does not reshuffle the list.
    var frozen by remember { mutableStateOf<List<Place>?>(null) }
    LaunchedEffect(livePlaces, activePart) {
        val pk = activePart ?: return@LaunchedEffect
        if (frozen == null && livePlaces != null) frozen = livePlaces!!.filter { pk == 0 || it.part == pk }
    }
    val placesById = remember(frozen, livePlaces) {
        (frozen.orEmpty() + livePlaces.orEmpty()).associateBy { it.id }
    }
    val order = frozen.orEmpty()
    var index by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(frozen) {
        val list = frozen ?: return@LaunchedEffect
        if (index < 0) {
            // A chosen place opens directly; otherwise continue from the first place not yet
            // shown to 1C, so a crash or app restart resumes where it stopped.
            index = when {
                startPlaceId >= 0 -> list.indexOfFirst { it.id == startPlaceId }
                else -> list.indexOfFirst { !it.verified }
            }.coerceAtLeast(0)
        }
    }

    KeepBright(prefs.reviewBrightness)
    var problemFor by remember { mutableStateOf<Place?>(null) }
    problemFor?.let { p ->
        ProblemDialog(
            p,
            onSave = { note -> scope.launch { Repo.setProblem(p.id, true, note) }; problemFor = null },
            onClear = { scope.launch { Repo.setProblem(p.id, false, "") }; problemFor = null },
            onDismiss = { problemFor = null },
        )
    }
    if (!problemsMode) ResumeSync(sessionId)

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().background(Orange).statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(
                when {
                    problemsMode -> "Проблемные — коды для 1С"
                    (activePart ?: 0) > 0 -> "Коды для 1С · часть $activePart из $partsTotal"
                    else -> "Коды для 1С"
                },
                color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (order.isNotEmpty() && index in order.indices) {
                Text("${index + 1} / ${order.size}", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(end = 12.dp))
            }
        }
        if (order.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { order.count { placesById[it.id]?.verified == true } / order.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            frozen == null -> Centered { Text("Загрузка…") }
            order.isEmpty() -> Centered { Text("Нет мест для показа") }
            index >= order.size -> Summary(order.mapNotNull { placesById[it.id] }, problemsMode, onBack, openProblems) {
                index = 0
            }
            index >= 0 -> {
                val place = placesById[order[index].id]
                if (place == null) {
                    Centered { Text("Место удалено") }
                } else {
                    PlaceCodes(
                        place = place,
                        showEan = prefs.reviewShowEan,
                        product = place.ean?.let { productMap[it] },
                        productMap = productMap,
                        modifier = Modifier.weight(1f),
                        onProblem = { problemFor = place },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { if (index > 0) index-- },
                        enabled = index > 0,
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) { Text("Назад", fontSize = 18.sp) }
                    Button(
                        onClick = {
                            val id = order[index].id
                            scope.launch { Repo.setVerified(id) }
                            index++
                        },
                        modifier = Modifier.weight(2f).height(64.dp),
                    ) { Text(if (index == order.size - 1) "Готово" else "Далее", fontSize = 20.sp) }
                }
            }
        }
    }
}

/** One cargo place: its QR on top and, if bound, the product EAN barcode below. */
@Composable
private fun PlaceCodes(
    place: Place,
    showEan: Boolean,
    product: ru.dnsflow.app.data.Product?,
    productMap: Map<String, ru.dnsflow.app.data.Product>,
    modifier: Modifier,
    onProblem: () -> Unit,
) {
    val info = Codes.parsePlace(place.raw)
    val is2d = place.format == "QR_CODE" || place.format == "DATA_MATRIX"
    val withEan = showEan && place.ean != null
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Место №${place.position}  ", fontSize = 15.sp, color = Color.Gray)
            Text(info.number, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        CodeImage(
            place.raw, place.format,
            Modifier.fillMaxWidth(if (is2d) (if (withEan) 0.55f else 0.85f) else 1f).aspectRatio(if (is2d) 1f else 2.6f),
        )
        info.printedAt?.let { Text("Этикетка от $it", color = Color.Gray, fontSize = 12.sp) }
        Text(
            "Скан с телефона: ${formatScanTime(place.scannedAt)}" + if (place.scannedBy.isNotBlank()) " · ${place.scannedBy}" else "",
            color = Color.DarkGray, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(6.dp))
        if (place.problem && place.problemNote.isNotBlank()) {
            Text("Проблема: ${place.problemNote}", color = ProblemRed, fontSize = 13.sp)
        }
        // Problem mark and photos share one row to leave room for the gap before the EAN.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (place.problem) {
                Button(onClick = onProblem, colors = ButtonDefaults.buttonColors(containerColor = ProblemRed)) {
                    Text("Проблема…")
                }
            } else {
                OutlinedButton(onClick = onProblem) { Text("Проблема…", color = ProblemRed) }
            }
            PhotoStrip(place, Modifier.weight(1f))
        }

        if (withEan) {
            // A wide empty band between the codes so the 1C scanner reads only the one it is aimed at.
            Spacer(Modifier.height(120.dp))
            Text("▼ штрихкод товара (EAN) ▼", color = Color.Gray, fontSize = 12.sp)
            CodeImage(place.ean!!, place.eanFormat ?: "EAN_13", Modifier.fillMaxWidth(0.8f).aspectRatio(3.4f))
        }
        Spacer(Modifier.height(6.dp))
        ProductLine(place.ean, product, Modifier.fillMaxWidth(), large = true)

        // Items the warehouse sent apart from the box under the same label.
        if (place.pieces > 1) {
            val eans = place.extraEans.lines().filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            Spacer(Modifier.height(10.dp))
            Text(
                "Отдельно от коробки: ${place.pieces - 1} шт." + if (eans.isEmpty()) " (EAN не сканировали)" else "",
                color = Color(0xFF6A1B9A), fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            if (showEan) eans.forEach { (ean, n) ->
                Spacer(Modifier.height(100.dp))
                Text("▼ отдельная часть" + if (n > 1) " ×$n ▼" else " ▼", color = Color.Gray, fontSize = 12.sp)
                CodeImage(ean, "EAN_13", Modifier.fillMaxWidth(0.8f).aspectRatio(3.4f))
                ProductLine(ean, productMap[ean], Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Summary(places: List<Place>, problemsMode: Boolean, onBack: () -> Unit, openProblems: () -> Unit, restart: () -> Unit) {
    val problems = places.count { it.problem && !it.problemResolved }
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Готово", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = OkGreen)
        Text("Показано мест: ${places.size}", fontSize = 18.sp)
        Text("Проблемных: $problems", fontSize = 18.sp, color = if (problems > 0) ProblemRed else Color.Unspecified)
        if (problems > 0 && !problemsMode) {
            Button(
                onClick = openProblems,
                colors = ButtonDefaults.buttonColors(containerColor = ProblemRed),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Открыть проблемные") }
        }
        OutlinedButton(onClick = restart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Показать сначала") }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Выход") }
    }
}

/** Keeps the screen on and at full brightness so the 1C scanner reads the code from it. */
@Composable
private fun KeepBright(maxBrightness: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(maxBrightness) {
        val window = activity.window
        val before = window.attributes.screenBrightness
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (maxBrightness) window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply { screenBrightness = before }
        }
    }
}
