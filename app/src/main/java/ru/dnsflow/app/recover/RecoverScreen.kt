package ru.dnsflow.app.recover

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.data.Repo
import ru.dnsflow.app.scan.Feedback
import ru.dnsflow.app.scan.bindBackCamera
import ru.dnsflow.app.ui.CodeImage
import ru.dnsflow.app.ui.OkGreen
import ru.dnsflow.app.ui.Orange
import ru.dnsflow.app.ui.ProblemRed
import ru.dnsflow.app.ui.TopBar
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private val printedFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
private const val AIM = 0.6f

private sealed interface Phase {
    data object Aiming : Phase
    data class Working(val progress: Float, val note: String) : Phase
    data class Done(val result: QrRecovery.Result, val gridQuality: Float) : Phase
    data object NoGrid : Phase
    /** The camera read the code after all. */
    data class Decoded(val raw: String) : Phase
}

/**
 * "Label damaged": the place number is read from the label (or typed), the remains of the QR are
 * photographed and the exact original code is rebuilt by [QrRecovery].
 */
@Composable
fun RecoverScreen(sessionId: Long, onBack: () -> Unit, onAdded: (placeId: Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastFrame = remember { AtomicReference<GridSampler.Frame?>(null) }
    var number by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(90L) }
    var phase by remember { mutableStateOf<Phase>(Phase.Aiming) }
    var chosen by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val votes = remember { GridVotes() }
    var gridFrames by remember { mutableStateOf(0) }
    var gridQuality by remember { mutableStateOf(0f) }
    var autoTried by remember { mutableStateOf(false) }
    val digits = number.filter { it.isDigit() }
    val numberOk = QrRecovery.qrNumber(digits) != null

    fun add(raw: String, format: String, note: String) {
        scope.launch {
            when (val r = Repo.addPlace(sessionId, raw, format)) {
                is Repo.AddResult.Added -> {
                    Repo.setProblem(r.place.id, true, note)
                    Feedback.ok(context)
                    onAdded(r.place.id)
                }
                is Repo.AddResult.Duplicate -> {
                    Feedback.error(context)
                    message = "Это место уже есть в приёмке (№${r.place.position})"
                }
            }
        }
    }

    fun recover() {
        val num = digits
        val frame = lastFrame.get()
        phase = Phase.Working(0f, "Ищу квадрат QR…")
        job = scope.launch {
            // Cells voted over the frames collected so far; a single frame only as a fallback.
            val vote = votes.vote() ?: withContext(Dispatchers.Default) {
                frame?.let { GridSampler.sample(it, AIM) }?.let { g -> GridVotes(1).also { it.add(g) }.vote() }
            }
            if (vote == null) { phase = Phase.NoGrid; Feedback.error(context); return@launch }
            phase = Phase.Working(0f, "Подбираю дату и время печати…")
            val to = LocalDate.now()
            val started = System.currentTimeMillis()
            val res = withContext(Dispatchers.Default) {
                QrRecovery.solve(vote.cells, num, to.minusDays(days), to, use = vote.use,
                    isCancelled = { job?.isCancelled == true },
                    progress = { p -> scope.launch { phase = Phase.Working(p, "Подбираю дату и время печати…") } })
            }
            android.util.Log.i("Recover", "frames=${vote.frames} certain=${vote.certain} searched=${res.searched} " +
                "in ${System.currentTimeMillis() - started}ms best=${res.best} margin=${res.margin} match=${res.match}")
            chosen = 0
            phase = Phase.Done(res, vote.certain)
            if (res.confident) Feedback.ok(context) else Feedback.error(context)
        }
    }

    fun retake() {
        votes.clear(); gridFrames = 0; gridQuality = 0f; autoTried = false; message = null
        phase = Phase.Aiming
    }

    // Start by itself once the number is read and enough good frames of the QR are collected.
    LaunchedEffect(numberOk, gridFrames, phase) {
        if (phase is Phase.Aiming && numberOk && gridFrames >= 5 && !autoTried) {
            autoTried = true
            recover()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("Этикетка повреждена", subtitle = "Восстановление QR по номеру места", onBack = { job?.cancel(); onBack() })
        RecoverCamera(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            paused = phase !is Phase.Aiming && phase !is Phase.NoGrid,
            onFrame = { lastFrame.set(it) },
            onGrid = { g -> votes.add(g); gridFrames = votes.count; gridQuality = votes.bestQuality },
            status = when {
                gridFrames == 0 -> "Ищу QR в рамке…"
                gridFrames < 5 -> "QR найден: кадров $gridFrames из 5, держите ровно"
                else -> "QR найден: $gridFrames кадров, качество ${(gridQuality * 100).toInt()}%"
            },
            statusOk = gridFrames >= 5,
            onNumber = { if (!typed && it != number) number = it },
            onCode = { raw, format ->
                if (phase is Phase.Aiming || phase is Phase.NoGrid) {
                    if (!Codes.isProduct(format) && raw.startsWith("mst")) { phase = Phase.Decoded(raw); Feedback.ok(context) }
                }
            },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val ph = phase) {
                Phase.Aiming, Phase.NoGrid -> {
                    Text("1. Номер места — 9 крупных цифр на этикетке. Камера подставит сама, проверьте.", fontSize = 14.sp)
                    OutlinedTextField(
                        value = number,
                        onValueChange = { v -> number = v.filter { it.isDigit() }.take(10); typed = true },
                        label = { Text("Номер места") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = number.isNotEmpty() && !numberOk,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "2. Наведите рамку на остатки QR так, чтобы были видны угловые квадраты, и подержите пару секунд — " +
                            "восстановление начнётся само, когда наберётся 5 чётких кадров.",
                        fontSize = 14.sp,
                    )
                    Text("Этикетка напечатана за последние:", fontSize = 13.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((d, label) in listOf(30L to "30 дн.", 90L to "90 дн.", 365L to "год")) {
                            val on = d == days
                            OutlinedButton(
                                onClick = { days = d },
                                colors = if (on) ButtonDefaults.outlinedButtonColors(containerColor = Orange, contentColor = Color.White)
                                else ButtonDefaults.outlinedButtonColors(),
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }
                    if (ph is Phase.NoGrid) {
                        Text(
                            "Не нашёл квадрат QR. Поднесите телефон ближе (QR примерно в пол-рамки), держите ровно, " +
                                "без бликов; должны быть видны угловые квадраты.",
                            color = ProblemRed,
                        )
                    }
                    message?.let { Text(it, color = ProblemRed) }
                    Button(
                        onClick = { message = null; recover() },
                        enabled = numberOk,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("Восстановить QR", fontSize = 17.sp) }
                    if (ph is Phase.NoGrid && numberOk) {
                        TextButton(onClick = {
                            add(digits.takeLast(9), "CODE_128", "Этикетка повреждена, QR не восстановлен — ввести номер ${digits.takeLast(9)} в 1С вручную")
                        }) { Text("Добавить только по номеру (QR не восстановлен)", color = ProblemRed) }
                    }
                }
                is Phase.Working -> {
                    Text(ph.note, fontWeight = FontWeight.Medium)
                    LinearProgressIndicator(progress = { ph.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { job?.cancel(); retake() }) { Text("Отмена") }
                }
                is Phase.Decoded -> {
                    Text("QR прочитался целиком", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Место ${Codes.parsePlace(ph.raw).number}")
                    Button(onClick = { add(ph.raw, "QR_CODE", "Этикетка повреждена, QR прочитан") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Добавить место")
                    }
                    TextButton(onClick = { phase = Phase.Aiming }) { Text("Назад") }
                }
                is Phase.Done -> ResultView(ph, chosen, onChoose = { chosen = it }, message = message,
                    onAdd = { c ->
                        val pct = (100 * (1f - c.mismatches / 625f)).toInt()
                        add(c.text, "QR_CODE", "Этикетка повреждена, QR восстановлен по номеру (совпадение $pct%, этикетка от ${c.printedAt.format(printedFmt)})")
                    },
                    onRetry = { retake() },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ResultView(
    ph: Phase.Done,
    chosen: Int,
    onChoose: (Int) -> Unit,
    message: String?,
    onAdd: (QrRecovery.Candidate) -> Unit,
    onRetry: () -> Unit,
) {
    val res = ph.result
    val best = res.best ?: run {
        Text("Не удалось подобрать код", color = ProblemRed)
        OutlinedButton(onClick = onRetry) { Text("Переснять") }
        return
    }
    if (res.confident) {
        Text("QR восстановлен", color = OkGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    } else {
        Text("Сомнительно: сверьте дату печати с этикеткой", color = ProblemRed, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
    Text(
        "Совпадение с уцелевшим кодом ${(res.match * 100).toInt()}%, отрыв от следующего варианта ${res.margin} клеток. " +
            "Проверено ${"%,d".format(res.searched).replace(',', ' ')} вариантов даты и времени.",
        fontSize = 13.sp, color = Color.Gray,
    )
    val options = res.candidates.take(if (res.confident) 1 else 3)
    options.forEachIndexed { i, c ->
        Card(
            Modifier.fillMaxWidth().clickable { onChoose(i) },
            colors = CardDefaults.cardColors(containerColor = if (i == chosen) Color(0xFFFFF3E0) else Color(0xFFF4F4F4)),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (options.size > 1) RadioButton(selected = i == chosen, onClick = { onChoose(i) })
                Column(Modifier.weight(1f)) {
                    Text(Codes.parsePlace(c.text).number, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Этикетка от ${c.printedAt.format(printedFmt)}", fontSize = 14.sp)
                    Text("Расхождений: ${c.mismatches}", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }
    }
    val c = options.getOrElse(chosen) { best }
    CodeImage(c.text, "QR_CODE", Modifier.fillMaxWidth(0.5f).aspectRatio(1f).align(Alignment.CenterHorizontally))
    message?.let { Text(it, color = ProblemRed) }
    Button(onClick = { onAdd(c) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Добавить место", fontSize = 17.sp) }
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Переснять") }
    Text("Место будет помечено «этикетка повреждена», чтобы его проверили при пике в 1С.", fontSize = 12.sp, color = Color.Gray)
}

/** Camera that keeps the latest frame for the grid search and reads the printed number. */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun RecoverCamera(
    modifier: Modifier,
    paused: Boolean,
    onFrame: (GridSampler.Frame) -> Unit,
    onGrid: (GridSampler.Grid) -> Unit,
    onNumber: (String) -> Unit,
    onCode: (String, String) -> Unit,
    status: String,
    statusOk: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val currentFrame by rememberUpdatedState(onFrame)
    val currentGrid by rememberUpdatedState(onGrid)
    val main = remember { ContextCompat.getMainExecutor(context) }
    val currentNumber by rememberUpdatedState(onNumber)
    val currentCode by rememberUpdatedState(onCode)
    val currentPaused by rememberUpdatedState(paused)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    if (granted) DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val barcodes = BarcodeScanning.getClient()
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var lastOcr = 0L
        run {
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || currentPaused) { proxy.close(); return@setAnalyzer }
                // Y plane copy for the grid search.
                val plane = proxy.planes[0]
                val w = proxy.width; val h = proxy.height
                val y = ByteArray(w * h)
                val buf = plane.buffer
                for (r in 0 until h) { buf.position(r * plane.rowStride); buf.get(y, r * w, w) }
                val frame = GridSampler.Frame(y, w, h)
                currentFrame(frame)
                // Look for the QR grid in every frame there is time for; good ones are voted on.
                GridSampler.sample(frame, AIM)?.let { g -> main.execute { currentGrid(g) } }
                val now = System.currentTimeMillis()
                if (now - lastOcr < 700) { proxy.close(); return@setAnalyzer }
                lastOcr = now
                val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                val b = barcodes.process(input)
                val t = text.process(input)
                Tasks.whenAllComplete(b, t).addOnCompleteListener {
                    // A failed task must not keep the frame open, or the camera stops delivering frames.
                    if (b.isSuccessful) b.result?.firstOrNull { !it.rawValue.isNullOrEmpty() }?.let { code ->
                        main.execute { currentCode(code.rawValue!!, Codes.formatName(code.format)) }
                    }
                    if (t.isSuccessful) t.result?.text?.let { s ->
                        // The big place number: 9 digits (sometimes read with a leading 0 dropped or added).
                        Regex("(?<!\\d)\\d{9,10}(?!\\d)").findAll(s.replace(" ", "")).map { it.value }
                            .firstOrNull { it.length == 9 }?.let { n -> main.execute { currentNumber(n) } }
                    }
                    proxy.close()
                }
            }
            val release = bindBackCamera(context, lifecycleOwner, listOf(preview, analysis))
            onDispose {
                release()
                analysis.clearAnalyzer()
                barcodes.close(); text.close(); executor.shutdown()
            }
        }
    }

    Box(modifier.clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxWidth(AIM).fillMaxHeight(AIM * 1.25f)
                    .border(3.dp, if (statusOk) OkGreen else Orange, RoundedCornerShape(8.dp))
            )
            Text(
                status,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .background(if (statusOk) OkGreen else Color(0xAA000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить камеру") }
        }
    }
}
