package ru.dnsflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dnsflow.app.App
import ru.dnsflow.app.data.Repo
import ru.dnsflow.app.update.Updater

@Composable
fun HomeScreen(
    openReceive: (Long) -> Unit,
    openReview: (Long) -> Unit,
    openJoin: () -> Unit,
    openHistory: () -> Unit,
    openProblems: () -> Unit,
    openSettings: () -> Unit,
) {
    val dao = App.instance.dao
    val open by remember { dao.openSession() }.collectAsState(initial = null)
    val pendingCheck by remember { dao.pendingCheck() }.collectAsState(initial = null)
    val problemCount by remember { dao.openProblemCount() }.collectAsState(initial = 0)
    val summaries by remember { dao.sessionSummaries() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val busy = open != null || pendingCheck != null

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Приёмка товара", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        UpdateCard()

        // Work in progress comes first: one tap to carry on.
        open?.let { s ->
            val count = summaries.firstOrNull { it.session.id == s.id }?.placeCount ?: 0
            WorkCard(
                title = "Идёт сканирование",
                subtitle = "${s.title}  ·  $count мест",
                action = "Продолжить сканирование",
                onClick = { openReceive(s.id) },
            )
        }
        pendingCheck?.let { c ->
            val total = summaries.firstOrNull { it.session.id == c.session.id }?.placeCount ?: 0
            WorkCard(
                title = "Коды для 1С не допоказаны",
                subtitle = "${c.session.title}  ·  осталось ${c.remaining} из $total",
                action = "Продолжить показ в 1С",
                onClick = { openReview(c.session.id) },
            )
        }

        BigButton("Новая приёмка", Icons.Default.Add, outlined = busy) {
            scope.launch { openReceive(Repo.newSession()) }
        }
        BigButton("Присоединиться к коллеге", Icons.Default.Share, outlined = true, onClick = openJoin)

        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("История", Icons.Default.List, Modifier.weight(1f), onClick = openHistory)
            Tile(
                if (problemCount > 0) "Проблемы ($problemCount)" else "Проблемы",
                Icons.Default.Warning, Modifier.weight(1f),
                alert = problemCount > 0, onClick = openProblems,
            )
            Tile("Настройки", Icons.Default.Settings, Modifier.weight(1f), onClick = openSettings)
        }
    }
}

@Composable
private fun WorkCard(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(subtitle, fontSize = 14.sp, color = Color.DarkGray, maxLines = 2)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(action, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Tile(text: String, icon: ImageVector, modifier: Modifier, alert: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(76.dp),
        colors = if (alert) ButtonDefaults.outlinedButtonColors(containerColor = ProblemRed, contentColor = Color.White)
        else ButtonDefaults.outlinedButtonColors(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(text, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BigButton(
    text: String,
    icon: ImageVector,
    outlined: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val mod = Modifier.fillMaxWidth().height(60.dp)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 17.sp, maxLines = 1)
        }
    }
    if (outlined) {
        OutlinedButton(onClick, mod) { content() }
    } else {
        Button(onClick, mod, colors = color?.let { ButtonDefaults.buttonColors(containerColor = it) } ?: ButtonDefaults.buttonColors()) { content() }
    }
}

/** A newer version on GitHub: shown on the home screen only, so scanning is never interrupted. */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val state by Updater.state.collectAsState()
    LaunchedEffect(Unit) { Updater.check(context) }
    val release = when (val st = state) {
        is Updater.State.Available -> st.release
        is Updater.State.Downloading -> st.release
        is Updater.State.NeedsPermission -> st.release
        is Updater.State.Failed -> st.release
        else -> null
    } ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Доступна версия ${release.version}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (release.notes.isNotBlank()) Text(release.notes, fontSize = 14.sp, color = Color.DarkGray, maxLines = 5)
            when (val st = state) {
                is Updater.State.Downloading -> Text("Скачивание… ${st.percent}%", fontSize = 15.sp)
                is Updater.State.NeedsPermission ->
                    Text("Разрешите установку из этого приложения и нажмите «Установить»", fontSize = 14.sp, color = ProblemRed)
                is Updater.State.Failed -> Text(st.message, fontSize = 14.sp, color = ProblemRed)
                else -> {}
            }
            Button(
                onClick = { App.instance.scope.launch { Updater.install(context.applicationContext, release) } },
                enabled = state !is Updater.State.Downloading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            ) {
                Text(if (state is Updater.State.NeedsPermission) "Установить" else "Обновить", fontSize = 16.sp)
            }
        }
    }
}
