package ru.dnsflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dnsflow.app.App
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.data.Place
import ru.dnsflow.app.data.Repo
import ru.dnsflow.app.scan.Feedback
import ru.dnsflow.app.scan.ScannerView
import ru.dnsflow.app.sync.Sync

@Composable
fun ReceiveScreen(
    sessionId: Long,
    onBack: () -> Unit,
    openReview: () -> Unit,
    openJoin: (Long) -> Unit,
    openDamaged: () -> Unit = {},
    recoveredPlaceId: Long = -1,
    onRecoveredHandled: () -> Unit = {},
) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by remember { app.dao.sessionFlow(sessionId) }.collectAsState(initial = null)
    val places by remember { app.dao.places(sessionId) }.collectAsState(initial = emptyList())
    val products by remember { app.dao.products() }.collectAsState(initial = emptyList())
    val productMap = remember(products) { products.associateBy { it.ean } }

    // Place waiting for its product EAN; null means we wait for a cargo place.
    var awaitingEanFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("Наведите камеру на QR этикетки") }
    var messageError by remember { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Place?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    // "Places only" mode: no EAN step. Remembered in settings, switched right here.
    var askEan by remember { mutableStateOf(app.prefs.askEan) }
    val takePhoto = rememberPhotoTaker()
    var problemFor by remember { mutableStateOf<Place?>(null) }
    // The same label scanned again: maybe an item sent apart from its box (asked, not assumed).
    var repeatOf by remember { mutableStateOf<Place?>(null) }
    // The EAN being waited for belongs to a separate piece, not to the box.
    var pieceEan by rememberSaveable { mutableStateOf(false) }
    val withPermissions = rememberSyncPermissions()
    ResumeSync(sessionId)
    val awaitingPlace = places.firstOrNull { it.id == awaitingEanFor }

    fun info(text: String) { message = text; messageError = false }
    fun error(text: String) { message = text; messageError = true; Feedback.error(context) }

    // A place added from the "label damaged" screen: continue with its EAN like a normal scan.
    LaunchedEffect(recoveredPlaceId) {
        if (recoveredPlaceId <= 0) return@LaunchedEffect
        val p = app.dao.place(recoveredPlaceId)
        onRecoveredHandled()
        if (p == null) return@LaunchedEffect
        val n = Codes.parsePlace(p.raw).number
        if (askEan) {
            awaitingEanFor = p.id
            info("✓ Место $n добавлено с повреждённой этикетки")
        } else {
            info("Место $n добавлено с повреждённой этикетки")
        }
    }

    fun handlePlace(raw: String, format: String) {
        // Scanning the next cargo place instead of an EAN skips the EAN of the previous one.
        val skipped = awaitingPlace?.takeIf { !pieceEan }?.let { Codes.parsePlace(it.raw).number }
        repeatOf = null
        pieceEan = false
        scope.launch {
            when (val r = Repo.addPlace(sessionId, raw, format)) {
                is Repo.AddResult.Added -> {
                    Feedback.ok(context)
                    val n = Codes.parsePlace(raw).number
                    val before = skipped?.let { "EAN для $it пропущен. " } ?: ""
                    if (askEan) {
                        awaitingEanFor = r.place.id
                        info("${before}✓ Место $n принято")
                    } else {
                        awaitingEanFor = null
                        info("${before}✓ Место $n принято (№${r.place.position})")
                    }
                }
                is Repo.AddResult.Duplicate -> {
                    val who = r.place.scannedBy.takeIf { it.isNotBlank() }?.let { ", отсканировал «$it»" } ?: ""
                    awaitingEanFor = null
                    repeatOf = r.place
                    error("Место ${Codes.parsePlace(raw).number} уже есть (№${r.place.position}$who)")
                }
            }
        }
    }

    fun handleCode(raw: String, format: String) {
        if (Sync.isInvite(raw)) {
            error("Это QR приглашения — его сканируют через «Присоединиться» на главном экране")
            return
        }
        val target = awaitingEanFor
        if (Codes.isProduct(format)) {
            if (target == null) {
                error("Это штрихкод товара. Сначала сканируйте грузовое место")
                return
            }
            val forPiece = pieceEan
            scope.launch {
                if (forPiece) Repo.addExtraEan(target, raw) else Repo.attachEan(target, raw, format)
                awaitingEanFor = null
                pieceEan = false
                Feedback.ok(context)
                info(if (forPiece) "✓ EAN отдельной части $raw" else "✓ EAN $raw привязан")
            }
        } else if (Codes.isPlaceCode(raw, format)) {
            // A new cargo place while waiting for EAN means the EAN was skipped.
            handlePlace(raw, format)
        } else {
            error("Это не код грузового места и не EAN товара")
        }
    }

    var menu by remember { mutableStateOf(false) }
    val problems = places.count { it.problem && !it.problemResolved }

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4))) {
        // Top bar: what this is and how far along; rarely needed actions live in the menu.
        Row(
            Modifier.fillMaxWidth().background(Orange).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(session?.title ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    "${places.size} мест" + (if (problems > 0) "  ·  проблем: $problems" else ""),
                    color = Color.White, fontSize = 13.sp,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Сканировать вместе с коллегой") },
                        onClick = { menu = false; withPermissions { showShare = true } },
                    )
                    DropdownMenuItem(
                        text = { Text(if (askEan) "Режим: только места (без EAN)" else "Режим: место + EAN товара") },
                        onClick = {
                            menu = false
                            askEan = !askEan
                            app.prefs.askEan = askEan
                            if (!askEan) awaitingEanFor = null
                        },
                    )
                }
            }
        }
        SyncBanner(sessionId) { showShare = true }

        Box(Modifier.fillMaxWidth().height(250.dp)) {
            ScannerView(
                modifier = Modifier.fillMaxSize(),
                paused = selected != null || confirmFinish || showShare || problemFor != null,
                torch = torch,
                pick = { codes ->
                    codes.firstOrNull { Sync.isInvite(it.first) } ?: Codes.pick(codes, waitingForEan = awaitingEanFor != null)
                },
                onCode = { raw, format -> handleCode(raw, format) },
            )
            // Camera controls right on the picture: light and the scanning mode.
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CameraChip(if (askEan) "место + EAN" else "только места", active = !askEan) {
                    askEan = !askEan
                    app.prefs.askEan = askEan
                    if (!askEan) awaitingEanFor = null
                }
                CameraChip(if (torch) "свет вкл" else "свет", active = torch) { torch = !torch }
            }
        }

        // The current step: what to scan now, the last result, and the actions for this step.
        val eanStep = awaitingPlace != null
        Column(
            Modifier.fillMaxWidth().background(if (eanStep) Color(0xFF1565C0) else Color(0xFF263238)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (eanStep && pieceEan) "EAN отдельной части места ${Codes.parsePlace(awaitingPlace!!.raw).number}"
                else if (eanStep) "Шаг 2: EAN товара для ${Codes.parsePlace(awaitingPlace!!.raw).number}"
                else if (askEan) "Шаг 1: QR грузового места" else "Сканируйте QR грузового места",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                message,
                color = if (messageError) Color(0xFFFFCDD2) else Color(0xFFB0BEC5),
                fontSize = 14.sp,
                fontWeight = if (messageError) FontWeight.Bold else FontWeight.Normal,
            )
            repeatOf?.let { rp ->
                // Same label again: an item the warehouse sent apart from the box, or just a repeat.
                val number = Codes.parsePlace(rp.raw).number
                Text(
                    "Товар пришёл отдельно от коробки с этой этикеткой? Добавьте его как часть места.",
                    color = Color.White, fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            repeatOf = null
                            scope.launch { Repo.addPiece(rp.id) }
                            Feedback.ok(context)
                            if (askEan) {
                                awaitingEanFor = rp.id
                                pieceEan = true
                                info("✓ Место $number: часть ${rp.pieces + 1}. Сканируйте EAN этой части")
                            } else {
                                info("✓ Место $number: часть ${rp.pieces + 1}")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange),
                        modifier = Modifier.weight(1f),
                    ) { Text("+ отдельная часть") }
                    OutlinedButton(onClick = { repeatOf = null; info("Повтор пропущен") }) { Text("Нет, повтор", color = Color.White) }
                }
            }
            if (eanStep) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { awaitingEanFor = null; pieceEan = false; info("EAN пропущен") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        modifier = Modifier.weight(1f),
                    ) { Text("Пропустить") }
                    Button(
                        onClick = { problemFor = awaitingPlace },
                        colors = ButtonDefaults.buttonColors(containerColor = ProblemRed),
                    ) { Text("Проблема") }
                    Button(
                        onClick = { takePhoto(awaitingPlace!!.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    ) { Text("Фото") }
                }
            } else if (repeatOf == null) {
                OutlinedButton(onClick = openDamaged, modifier = Modifier.fillMaxWidth()) {
                    Text("QR не читается — этикетка повреждена", color = Color.White)
                }
            }
        }

        Text(
            if (places.isEmpty()) "Пока пусто — наведите камеру на этикетку" else "Отсканировано: ${places.size}  (нажмите на место — действия)",
            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp, top = 8.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            items(places.asReversed(), key = { it.id }, contentType = { "place" }) { p ->
                PlaceCard(p, productMap[p.ean], highlighted = p.id == awaitingEanFor) { selected = p }
            }
        }

        Button(
            onClick = { confirmFinish = true },
            enabled = places.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
        ) { Text("Готово — показать коды для 1С", fontSize = 17.sp) }
    }

    selected?.let { p ->
        PlaceActionsDialog(
            place = p,
            onDismiss = { selected = null },
            onScanEan = { awaitingEanFor = p.id; pieceEan = false; info("Сканируйте EAN для места ${Codes.parsePlace(p.raw).number}"); selected = null },
            onScanPieceEan = { awaitingEanFor = p.id; pieceEan = true; info("Сканируйте EAN отдельной части"); selected = null },
            onRemoveEan = { scope.launch { Repo.attachEan(p.id, null, null) }; selected = null },
            onProblem = { flag, note -> scope.launch { Repo.setProblem(p.id, flag, note) }; selected = null },
            onDelete = {
                scope.launch { Repo.deletePlace(p.id) }
                if (awaitingEanFor == p.id) awaitingEanFor = null
                selected = null
            },
        )
    }

    problemFor?.let { p ->
        ProblemDialog(
            p,
            onSave = { note -> scope.launch { Repo.setProblem(p.id, true, note) }; problemFor = null; info("Место помечено как проблемное") },
            onClear = { scope.launch { Repo.setProblem(p.id, false, "") }; problemFor = null },
            onDismiss = { problemFor = null },
        )
    }

    if (showShare) ShareDialog(sessionId, onDismiss = { showShare = false }, onJoinOther = { openJoin(sessionId) })

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Завершить сканирование?") },
            text = {
                Text("Мест: ${places.size}, проблемных: ${places.count { it.problem }}.\nДальше коды будут показаны по порядку для сканирования в 1С. Вернуться к сканированию можно из истории.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    scope.launch { Repo.finishSession(sessionId); openReview() }
                }) { Text("Завершить") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun PlaceCard(
    p: Place,
    product: ru.dnsflow.app.data.Product?,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val info = remember(p.raw) { Codes.parsePlace(p.raw) }
    val time = remember(p.scannedAt) { formatScanTime(p.scannedAt).takeLast(8) }
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).combinedClickable(onLongClick = onLongClick, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                highlighted -> Color(0xFFE3F2FD)
                p.problem && !p.problemResolved -> Color(0xFFFFEBEE)
                else -> Color.White
            }
        ),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${p.position}.", color = Color.Gray, modifier = Modifier.width(40.dp))
                Text(info.number, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                val photoCount = p.photos.lines().count { it.isNotBlank() }
                if (p.pieces > 1) { Badge("частей ${p.pieces}", Color(0xFF6A1B9A)); Spacer(Modifier.width(4.dp)) }
                if (photoCount > 0) { Badge("фото $photoCount", Color(0xFF455A64)); Spacer(Modifier.width(4.dp)) }
                if (p.verified) { Badge("✓ 1С", OkGreen); Spacer(Modifier.width(4.dp)) }
                if (p.problem) Badge(if (p.problemResolved) "решено" else "проблема", if (p.problemResolved) OkGreen else ProblemRed)
            }
            if (p.problem && p.problemNote.isNotBlank()) Text(p.problemNote, color = ProblemRed, fontSize = 13.sp)
            if (p.ean != null) ProductLine(p.ean, product)
            separatePieces(p)?.let { Text(it, fontSize = 13.sp, color = Color(0xFF6A1B9A)) }
            Text(
                listOfNotNull("скан $time", p.scannedBy.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                fontSize = 12.sp, color = Color.Gray,
            )
        }
    }
}

@Composable
fun PlaceActionsDialog(
    place: Place,
    onDismiss: () -> Unit,
    onScanEan: (() -> Unit)?,
    onScanPieceEan: (() -> Unit)? = null,
    onRemoveEan: () -> Unit,
    onProblem: (Boolean, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var note by remember { mutableStateOf(place.problemNote) }
    var confirmDelete by remember { mutableStateOf(false) }
    val info = Codes.parsePlace(place.raw)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Место ${info.number}") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                info.printedAt?.let { Text("Этикетка от $it", fontSize = 13.sp, color = Color.Gray) }
                Text("Код: ${place.raw}", fontSize = 12.sp, color = Color.Gray)
                Text("Проблема", fontWeight = FontWeight.Bold)
                ProblemEditor(note) { note = it }
                Button(
                    onClick = { onProblem(true, note) },
                    colors = ButtonDefaults.buttonColors(containerColor = ProblemRed),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (place.problem) "Сохранить проблему" else "Пометить проблемным") }
                if (place.problem) {
                    OutlinedButton(onClick = { onProblem(false, "") }, modifier = Modifier.fillMaxWidth()) { Text("Снять пометку") }
                }
                // Live copy so photos added from this dialog show up right away.
                val live by remember(place.id) { App.instance.dao.placeFlow(place.id) }.collectAsState(initial = place)
                PhotoStrip(live ?: place)
                // Items sent apart from the box under this same label.
                val scope = rememberCoroutineScope()
                val pieces = (live ?: place).pieces
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Частей (коробка + отдельно): $pieces", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    OutlinedButton(onClick = { scope.launch { Repo.removePiece(place.id) } }, enabled = pieces > 1) { Text("−") }
                    OutlinedButton(onClick = { scope.launch { Repo.addPiece(place.id) } }) { Text("+") }
                }
                separatePieces(live ?: place)?.let { Text(it, fontSize = 13.sp, color = Color(0xFF6A1B9A)) }
                if (onScanPieceEan != null && pieces > 1) {
                    OutlinedButton(onClick = onScanPieceEan, modifier = Modifier.fillMaxWidth()) { Text("Сканировать EAN отдельной части") }
                }
                if (onScanEan != null) {
                    OutlinedButton(onClick = onScanEan, modifier = Modifier.fillMaxWidth()) {
                        Text(if (place.ean == null) "Сканировать EAN" else "Пересканировать EAN")
                    }
                }
                if (place.ean != null) {
                    OutlinedButton(onClick = onRemoveEan, modifier = Modifier.fillMaxWidth()) { Text("Отвязать EAN") }
                }
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Точно удалить?" else "Удалить место", color = ProblemRed)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** "Отдельно: 4600…×3, 4680…" for a place with items sent apart from its box, or null. */
fun separatePieces(p: Place): String? {
    if (p.pieces <= 1) return null
    val eans = p.extraEans.lines().filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val listed = eans.entries.joinToString(", ") { (e, n) -> if (n > 1) "$e ×$n" else e }
    val unscanned = p.pieces - 1 - eans.values.sum()
    return "Отдельно от коробки: ${p.pieces - 1} шт." +
        (if (listed.isNotEmpty()) " — $listed" else "") +
        (if (unscanned > 0 && listed.isNotEmpty()) " (+$unscanned без EAN)" else "")
}
