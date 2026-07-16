package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manual birth-date input with a device-independent day-first format. */
@Composable
fun DmyDateInputDialog(
    initialDateMillis: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val formatter = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.UK).apply { isLenient = false }
    }
    var text by remember(initialDateMillis) {
        mutableStateOf(initialDateMillis?.let { formatter.format(Date(it)) } ?: "")
    }
    val selectedMillis = remember(text) {
        parseDmyDate(text, formatter)
    }
    val valid = selectedMillis != null && selectedMillis <= System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ημερομηνία γέννησης")) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { typed ->
                        text = typed.filter { it.isDigit() || it == '/' }.take(10)
                    },
                    label = { Text("DD/MM/YYYY") },
                    placeholder = { Text("DD/MM/YYYY") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !valid,
                )
                if (text.isNotBlank() && !valid) {
                    Text(tr("Γράψε την ημερομηνία ως DD/MM/YYYY."))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedMillis?.let(onConfirm) },
                enabled = valid,
            ) { Text(tr("Εντάξει")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Άκυρο")) }
        },
    )
}

private fun parseDmyDate(input: String, formatter: SimpleDateFormat): Long? {
    if (!Regex("""\d{2}/\d{2}/\d{4}""").matches(input)) return null
    return runCatching {
        formatter.parse(input)?.takeIf { formatter.format(it) == input }?.time
    }.getOrNull()
}
