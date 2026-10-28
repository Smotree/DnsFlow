package ru.dnsflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import ru.dnsflow.app.export.Export
import androidx.compose.ui.text.style.TextAlign
import ru.dnsflow.app.data.Session
import ru.dnsflow.app.sync.Sync

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(Orange).statusBarsPadding().padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
            subtitle?.let { Text(it, color = Color.White, fontSize = 12.sp) }
        }
        actions()
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, openSession: (Long) -> Unit) {
    val sessions by remember { App.instance.dao.sessionSummaries() }.collectAsState(initial = null)
    Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4))) {
        TopBar("История приёмок", onBack = onBack)
        val list = sessions
        when {
            list == null -> Centered { Text("Загрузка…") }
            list.isEmpty() -> Centered { Text("Приёмок пока нет", color = Color.Gray) }
            else -> LazyColumn(contentPadding = PaddingValues(8.dp)) {
                items(list, key = { it.session.id }) { s ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openSession(s.session.id) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.session.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (s.session.finishedAt == null) Badge("в работе", Orange)
                            }
                            Text("Мест: ${s.placeCount}  ·  с EAN: ${s.eanCount}", fontSize = 13.sp, color = Color.DarkGray)
                            if (s.problemCount > 0) Text("Проблемных: ${s.problemCount}", fontSize = 13.sp, color = ProblemRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionScreen(
    sessionId: Long,
    onBack: () -> Unit,
    openReview: (placeId: Long, part: Int) -> Unit,
    openReceive: () -> Unit,
    openJoin: (Long) -> Unit,
) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by remember { app.dao.sessionFlow(sessionId) }.collectAsState(initial = null)
    val places by remember { app.dao.places(sessionId) }.collectAsState(initial = emptyList())
    val products by remember { app.dao.products() }.collectAsState(initial = emptyList())
    val productMap = remember(products) { products.associateBy { it.ean } }
    var selected by remember { mutableStateOf<Place?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    var showShare by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    val withPermissions = rememberSyncPermissions()
    ResumeSync(sessionId)

    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4))) {
        TopBar(session?.title ?: "", onBack = onBack) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Ещё", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; rename = session?.title ?: "" })
                    DropdownMenuItem(text = { Text("Удалить приёмку", color = ProblemRed) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        SyncBanner(sessionId) { showShare = true }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The numbers that matter at a glance.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val extra = places.sumOf { it.pieces - 1 }
                Stat(if (extra > 0) "мест (+$extra отд.)" else "мест", "${places.size}", Modifier.weight(1f))
                Stat("в 1С", "${places.count { it.verified }}/${places.size}", Modifier.weight(1f), good = places.isNotEmpty() && places.all { it.verified })
                Stat("проблем", "${places.count { it.problem && !it.problemResolved }}", Modifier.weight(1f), bad = places.any { it.problem && !it.problemResolved })
            }
            // Main action.
            val s = session
            val split = s != null && s.splitParts > 1 && s.myPart > 0
            val mine = if (split) places.filter { it.part == s!!.myPart } else places
            val left = mine.count { !it.verified }
            Button(
                onClick = { openReview(-1, -1) },
                enabled = mine.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                val what = if (split) "Мои коды для 1С (часть ${s!!.myPart} из ${s.splitParts})" else "Коды для 1С"
                Text(
                    when {
                        left == mine.size -> "$what"
                        left > 0 -> "$what — осталось $left"
                        else -> "$what — все показаны"
                    },
                    fontSize = 16.sp,
                )
            }
            if (s != null && s.splitParts > 1) {
                val noPart = places.count { it.part == 0 }
                Text(
                    (1..s.splitParts).joinToString("  ·  ") { k ->
                        val inPart = places.filter { it.part == k }
                        "Часть $k: ${inPart.count { it.verified }}/${inPart.size}"
                    } + (if (noPart > 0) "  ·  без части: $noPart" else ""),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = { openReview(-1, 0) }) { Text("Показать все коды, не только мои") }
            }

            // Everything else in one row.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionTile("Сканировать", Modifier.weight(1f)) { scope.launch { Repo.reopenSession(sessionId); openReceive() } }
                ActionTile("Вместе", Modifier.weight(1f)) { withPermissions { showShare = true } }
                ActionTile("Разделить", Modifier.weight(1f), enabled = places.isNotEmpty()) { showSplit = true }
                ActionTile("Excel", Modifier.weight(1f)) { scope.launch { Export.shareSession(context, sessionId) } }
            }
        }
        Text(
            "Места (нажмите — QR и EAN, удерживайте — действия)",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(start = 12.dp),
        )
        LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            items(places, key = { it.id }, contentType = { "place" }) { p ->
                PlaceCard(p, productMap[p.ean], onLongClick = { selected = p }) { openReview(p.id, 0) }
            }
        }
    }

    selected?.let { p ->
        PlaceActionsDialog(
            place = p,
            onDismiss = { selected = null },
            onScanEan = null,
            onRemoveEan = { scope.launch { Repo.attachEan(p.id, null, null) }; selected = null },
            onProblem = { flag, note -> scope.launch { Repo.setProblem(p.id, flag, note) }; selected = null },
            onDelete = { scope.launch { Repo.deletePlace(p.id) }; selected = null },
        )
    }

    if (showShare) ShareDialog(sessionId, onDismiss = { showShare = false }, onJoinOther = { openJoin(sessionId) })
    if (showSplit) session?.let { SplitDialog(it, places.size) { showSplit = false } }

    if (confirmDelete) {
        val problems = places.count { it.problem && !it.problemResolved }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить приёмку?") },
            text = {
                Text(
                    "Будут удалены все ${places.size} мест." +
                        if (problems > 0) "\nВнимание: среди них $problems нерешённых проблемных — они тоже пропадут." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { Repo.deleteSession(sessionId); onBack() }
                }) { Text("Удалить", color = ProblemRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    rename?.let { current ->
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Название приёмки") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("Например, номер машины") }) },
            confirmButton = {
                TextButton(onClick = {
                    rename = null
                    if (text.isNotBlank()) scope.launch { Repo.renameSession(sessionId, text.trim()) }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun ProblemsScreen(onBack: () -> Unit, openReview: (Long, Long) -> Unit, reviewAll: () -> Unit) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    val rows by remember { app.dao.problems() }.collectAsState(initial = null)
    val products by remember { app.dao.products() }.collectAsState(initial = emptyList())
    val productMap = remember(products) { products.associateBy { it.ean } }
    val open = rows.orEmpty().count { !it.place.problemResolved }

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F4F4))) {
        TopBar("Проблемные места", subtitle = "Не решено: $open  ·  всего: ${rows.orEmpty().size}", onBack = onBack)
        if (open > 0) {
            Button(
                onClick = reviewAll,
                colors = ButtonDefaults.buttonColors(containerColor = ProblemRed),
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(52.dp),
            ) { Text("Показать нерешённые коды по порядку") }
        }
        val list = rows
        when {
            list == null -> Centered { Text("Загрузка…") }
            list.isEmpty() -> Centered { Text("Проблемных мест нет", color = Color.Gray) }
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                items(list, key = { it.place.id }) { row ->
                    val p = row.place
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openReview(p.sessionId, p.id) },
                        colors = CardDefaults.cardColors(containerColor = if (p.problemResolved) Color.White else Color(0xFFFFEBEE)),
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = p.problemResolved,
                                onCheckedChange = { scope.launch { Repo.setResolved(p.id, it) } },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(Codes.parsePlace(p.raw).number, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("${row.sessionTitle}, место №${p.position}", fontSize = 12.sp, color = Color.Gray)
                                if (p.problemNote.isNotBlank()) Text(p.problemNote, color = ProblemRed, fontSize = 13.sp)
                                Spacer(Modifier.height(2.dp))
                                ProductLine(p.ean, productMap[p.ean])
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Splits the 1C check into equal parts. Every phone with the same list picks its own part;
 * when the phones are connected the split is also sent to the others.
 */
@Composable
fun SplitDialog(session: Session, total: Int, onDismiss: () -> Unit) {
    val sync by Sync.state.collectAsState()
    val hosting = sync.role == Sync.Role.HOST && sync.sessionId == session.id && sync.peers.isNotEmpty()
    var parts by remember { mutableStateOf(session.splitParts.takeIf { it > 1 } ?: maxOf(2, sync.peers.size + 1)) }
    var mine by remember { mutableStateOf(session.myPart.takeIf { it in 1..parts } ?: 1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Разделить коды для 1С") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hosting) {
                    Button(onClick = { Sync.splitBetweenPhones(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Поровну между подключёнными (${sync.peers.size + 1})")
                    }
                    Text("или вручную:", fontSize = 13.sp, color = Color.Gray)
                }
                Text("На сколько телефонов:")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (2..5).forEach { n ->
                        ChoiceButton(n == parts, Modifier.weight(1f), { parts = n; if (mine > n) mine = 1 }) { Text("$n") }
                    }
                }
                Text("Моя часть:")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..parts).forEach { k ->
                        // Same rule as Repo.split: place i goes to part i * parts / total + 1.
                        val from = ((k - 1) * total + parts - 1) / parts + 1
                        val to = (k * total + parts - 1) / parts
                        ChoiceButton(k == mine, Modifier.weight(1f), { mine = k }) {
                            Text("$k\n$from–$to", fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
                Text(
                    "Всего $total мест, примерно по ${(total + parts - 1) / parts} на телефон. Делите, когда все места уже отсканированы: " +
                        "на каждом телефоне выберите одинаковое число частей и свою часть.",
                    fontSize = 12.sp, color = Color.Gray,
                )
                if (session.splitParts > 1) {
                    TextButton(onClick = {
                        App.instance.scope.launch { Repo.split(session.id, 1); Repo.setMyPart(session.id, 0, 0) }
                        onDismiss()
                    }) { Text("Отменить деление", color = ProblemRed) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                App.instance.scope.launch { Repo.split(session.id, parts); Repo.setMyPart(session.id, parts, mine) }
                onDismiss()
            }) { Text("Разделить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ChoiceButton(selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) ButtonDefaults.outlinedButtonColors(containerColor = Orange, contentColor = Color.White)
        else ButtonDefaults.outlinedButtonColors(),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier,
    ) { content() }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, good: Boolean = false, bad: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = when { bad -> Color(0xFFFFEBEE); good -> Color(0xFFE8F5E9); else -> Color.White }
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = when { bad -> ProblemRed; good -> OkGreen; else -> Color.Black })
            Text(label, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun ActionTile(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) { Text(text, fontSize = 13.sp, maxLines = 1) }
}
