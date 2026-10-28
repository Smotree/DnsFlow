package ru.dnsflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dnsflow.app.data.Codes
import ru.dnsflow.app.data.Place

/** Typical acceptance problems, one tap each; they go into the problem note. */
val PROBLEM_REASONS = listOf(
    "Не оригинальная коробка",
    "Позиции не сходятся",
    "EAN не подходит",
    "Не тот товар (пересорт)",
    "Коробка повреждена",
    "Коробка вскрыта",
    "Мокрая / грязная",
    "Этикетка повреждена",
    "Нет места в 1С",
    "Лишнее место",
)

/** Splits a saved note back into the quick reasons it contains and the free text. */
private fun splitNote(note: String): Pair<Set<String>, String> {
    val parts = note.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return parts.filter { it in PROBLEM_REASONS }.toSet() to parts.filterNot { it in PROBLEM_REASONS }.joinToString("; ")
}

/** Chips for the quick reasons plus a free comment; the result is "reason; reason; comment". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProblemEditor(note: String, onNote: (String) -> Unit) {
    val (initialReasons, initialText) = remember { splitNote(note) }
    var reasons by remember { mutableStateOf(initialReasons) }
    var text by remember { mutableStateOf(initialText) }
    fun emit() = onNote((PROBLEM_REASONS.filter { it in reasons } + text.trim()).filter { it.isNotEmpty() }.joinToString("; "))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PROBLEM_REASONS.forEach { r ->
                FilterChip(
                    selected = r in reasons,
                    onClick = { reasons = if (r in reasons) reasons - r else reasons + r; emit() },
                    label = { Text(r, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFCDD2)),
                )
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; emit() },
            label = { Text("Своими словами (необязательно)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Marks a place as a problem with reasons and a comment, or clears the mark. */
@Composable
fun ProblemDialog(place: Place, onSave: (note: String) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf(place.problemNote) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проблема: место ${Codes.parsePlace(place.raw).number}") },
        text = { ProblemEditor(note) { note = it } },
        confirmButton = {
            Button(
                onClick = { onSave(note) },
                colors = ButtonDefaults.buttonColors(containerColor = ProblemRed),
            ) { Text("Сохранить") }
        },
        dismissButton = {
            if (place.problem) OutlinedButton(onClick = onClear) { Text("Снять пометку") }
            else TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
