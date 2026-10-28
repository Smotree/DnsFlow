package ru.dnsflow.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.dnsflow.app.App
import ru.dnsflow.app.MainActivity
import ru.dnsflow.app.export.Export
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoreError by remember { mutableStateOf(false) }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (Export.restoreBackup(context, uri)) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                exitProcess(0)
            } else {
                restoreError = true
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("Настройки и данные", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            SectionTitle("Сканирование")
            Toggle("Предлагать EAN после грузового места", "Можно пропустить кнопкой", prefs.askEan) { prefs.askEan = it }
            Toggle("Звук при скане", null, prefs.sound) { prefs.sound = it }
            Toggle("Вибрация при скане", null, prefs.vibrate) { prefs.vibrate = it }

            var device by remember { mutableStateOf(prefs.deviceName) }
            OutlinedTextField(
                value = device,
                onValueChange = { device = it; prefs.deviceName = it },
                label = { Text("Имя телефона / сотрудника") },
                supportingText = { Text("Видно у мест после объединения приёмок") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Показ кодов для 1С")
            Toggle("Максимальная яркость", "Сканер 1С лучше читает яркий экран", prefs.reviewBrightness) { prefs.reviewBrightness = it }
            Toggle("Показывать штрихкод EAN под QR места", "Чтобы отсканировать и его в 1С", prefs.reviewShowEan) { prefs.reviewShowEan = it }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Поиск товара на сайте DNS")
            Toggle("Искать товар по EAN автоматически", "Название, код и фото подгружаются в фоне", prefs.autoLookup) {
                prefs.autoLookup = it
                if (it) app.lookup.retryFailed()
            }
            OutlinedButton(onClick = { app.lookup.retryFailed() }, modifier = Modifier.fillMaxWidth()) {
                Text("Повторить поиск ненайденных")
            }
            OutlinedButton(onClick = { app.lookup.recheckAll() }, modifier = Modifier.fillMaxWidth()) {
                Text("Перепроверить все товары")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Данные")
            Text(
                "Все приёмки и проблемные места хранятся в памяти телефона, пока вы их не удалите. " +
                    "Сделайте резервную копию перед сменой или сбросом телефона.",
                fontSize = 13.sp, color = Color.Gray,
            )
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { Export.shareAll(context) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Экспорт всех приёмок в CSV (Excel)")
                }
                OutlinedButton(onClick = { scope.launch { Export.shareBackup(context) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Резервная копия базы")
                }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Восстановить из копии", color = ProblemRed)
                }
            }
        }
    }

    if (restoreError) {
        AlertDialog(
            onDismissRequest = { restoreError = false },
            title = { Text("Не удалось восстановить") },
            text = { Text("Выбранный файл не похож на резервную копию приложения.") },
            confirmButton = { TextButton(onClick = { restoreError = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            subtitle?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
        }
        Switch(checked = checked, onCheckedChange = { checked = it; onChange(it) })
    }
}
