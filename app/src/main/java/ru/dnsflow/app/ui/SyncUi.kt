package ru.dnsflow.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ru.dnsflow.app.scan.Feedback
import ru.dnsflow.app.scan.ScannerView
import ru.dnsflow.app.sync.Sync

/** Asks for the Bluetooth / nearby-devices permissions, then runs the action. */
@Composable
fun rememberSyncPermissions(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pending?.invoke() else denied = true
        pending = null
    }
    if (denied) {
        AlertDialog(
            onDismissRequest = { denied = false },
            title = { Text("Нужны разрешения") },
            text = { Text("Для связи телефонов напрямую нужны «Устройства поблизости» (Bluetooth / Wi‑Fi). Разрешите их и включите Bluetooth.") },
            confirmButton = { TextButton(onClick = { denied = false }) { Text("OK") } },
        )
    }
    return { action ->
        val missing = Sync.permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pending = action
            launcher.launch(missing.toTypedArray())
        }
    }
}

/** One-line status of the shared acceptance, shown under the top bar. */
@Composable
fun SyncBanner(sessionId: Long, onClick: () -> Unit) {
    val s by Sync.state.collectAsState()
    val context = LocalContext.current
    val withPermissions = rememberSyncPermissions()
    if (s.role == Sync.Role.NONE || s.sessionId != sessionId) return
    // With an error, a tap fixes what is missing (permissions, location switch) and retries.
    val fix: () -> Unit = {
        withPermissions {
            if (!Sync.locationOn()) {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Sync.retry()
        }
    }
    val text = when {
        s.error != null -> "Связь: ${s.error}"
        s.role == Sync.Role.HOST && s.peers.isEmpty() -> "Совместная приёмка: ждём подключения — нажмите, чтобы показать QR"
        s.role == Sync.Role.HOST -> "Совместно с: ${s.peers.joinToString { it.name }}"
        s.peers.isEmpty() -> "Ищем телефон «${s.hostName}»… держите телефоны рядом"
        else -> "Подключено к «${s.hostName}» · список общий"
    }
    val color = when {
        s.error != null -> ProblemRed
        s.peers.isEmpty() -> Color(0xFF8D6E00)
        else -> OkGreen
    }
    TextButton(
        onClick = if (s.error != null) fix else onClick,
        modifier = Modifier.fillMaxWidth().background(color),
        shape = androidx.compose.ui.graphics.RectangleShape,
    ) {
        if (s.peers.isEmpty() && s.error == null) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** Host side: invite QR for the other phones, who is connected, split and stop. */
@Composable
fun ShareDialog(sessionId: Long, onDismiss: () -> Unit, onJoinOther: (() -> Unit)? = null) {
    val s by Sync.state.collectAsState()
    val hosting = s.role == Sync.Role.HOST && s.sessionId == sessionId
    val guest = s.role == Sync.Role.GUEST && s.sessionId == sessionId
    LaunchedEffect(Unit) { if (!guest) Sync.host(sessionId) }
    var splitDone by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (guest) "Совместная приёмка" else "Сканировать вместе") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (hosting) {
                    Text("На втором телефоне: главный экран → «Присоединиться» → наведите на этот QR.", fontSize = 13.sp)
                    CodeImage(Sync.inviteText(s), "QR_CODE", Modifier.fillMaxWidth(0.8f).aspectRatio(1f))
                }
                if (guest) Text("Вы подключаетесь к телефону «${s.hostName}». Сканы сразу видны у всех.", fontSize = 13.sp)
                Text(
                    if (s.peers.isEmpty()) "Подключённых телефонов нет" else "Подключены: ${s.peers.joinToString { it.name }}",
                    fontWeight = FontWeight.Bold,
                )
                s.error?.let { Text(it, color = ProblemRed, fontSize = 12.sp) }
                if (hosting && s.peers.isNotEmpty()) {
                    Button(onClick = { Sync.splitBetweenPhones(); splitDone = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Разделить коды для 1С поровну на ${s.peers.size + 1}")
                    }
                    if (splitDone) Text("Готово: каждый телефон показывает свою часть кодов.", color = OkGreen, fontSize = 13.sp)
                }
                if (onJoinOther != null && !guest) {
                    TextButton(onClick = { Sync.stop(); onDismiss(); onJoinOther() }) {
                        Text("Старший — другой телефон? Присоединить эту приёмку к нему", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                TextButton(onClick = { Sync.stop(); onDismiss() }) { Text("Отключить совместную работу", color = ProblemRed) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** Guest side: scan the host's invite QR. */
@Composable
fun JoinScreen(fromSessionId: Long?, onBack: () -> Unit, joined: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val withPermissions = rememberSyncPermissions()
    var message by remember { mutableStateOf("Наведите камеру на QR «Сканировать вместе» на телефоне старшего") }
    Column(Modifier.fillMaxSize().background(Color(0xFF333333))) {
        TopBar(
            "Присоединиться к приёмке",
            subtitle = if (fromSessionId != null) "Уже отсканированные места этой приёмки объединятся с общей" else null,
            onBack = onBack,
        )
        ScannerView(Modifier.fillMaxWidth().height(380.dp)) { raw, _ ->
            if (!Sync.isInvite(raw)) {
                message = "Это не QR приглашения. Откройте приёмку на другом телефоне и нажмите «Вместе»"
                Feedback.error(context)
                return@ScannerView
            }
            Feedback.ok(context)
            withPermissions {
                scope.launch { Sync.join(raw, fromSessionId)?.let(joined) ?: run { message = "Не удалось прочитать приглашение" } }
            }
        }
        Text(message, color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
        Text(
            "Телефоны соединяются напрямую по Bluetooth / Wi‑Fi, интернет не нужен. Держите их рядом, Bluetooth должен быть включён.",
            color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Turns the shared mode of this acceptance back on after an app restart. */
@Composable
fun ResumeSync(sessionId: Long) {
    LaunchedEffect(sessionId) {
        ru.dnsflow.app.App.instance.dao.session(sessionId)?.let(Sync::resume)
    }
}
