package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manual birth-date input with a device-independent day-first format. Digits are auto-slashed
 * (so the numeric keyboard needs no "/" key) and a calendar button opens a tap-to-pick grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmyDateInputDialog(
    initialDateMillis: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    title: String? = null,
) {
    val formatter = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.UK).apply { isLenient = false }
    }
    var fieldValue by remember(initialDateMillis) {
        val initialText = initialDateMillis?.let { formatter.format(Date(it)) } ?: ""
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    var showCalendar by remember { mutableStateOf(false) }
    val text = fieldValue.text
    val selectedMillis = remember(text) { parseDmyDate(text, formatter) }
    val valid = selectedMillis != null && selectedMillis <= System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: tr("Ημερομηνία γέννησης")) },
        text = {
            Column {
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { typed ->
                        // Keep the caret after the automatically inserted slash. With a plain
                        // String value Compose can restore a stale selection and scramble the
                        // next digits (for example 02/62/2600 instead of 02/06/2026).
                        val formatted = formatDmyDigits(typed.text)
                        fieldValue = TextFieldValue(
                            text = formatted,
                            selection = TextRange(formatted.length),
                        )
                    },
                    label = { Text("DD/MM/YYYY") },
                    placeholder = { Text("DD/MM/YYYY") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotBlank() && !valid,
                    trailingIcon = {
                        IconButton(onClick = { showCalendar = true }) {
                            Icon(
                                Icons.Filled.CalendarMonth,
                                contentDescription = tr("Άνοιγμα ημερολογίου"),
                            )
                        }
                    },
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

    if (showCalendar) {
        val today = System.currentTimeMillis()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedMillis ?: initialDateMillis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= today
            },
        )
        // The picker reports UTC midnight; format in UTC so the day never shifts.
        val utcFormatter = remember {
            SimpleDateFormat("dd/MM/yyyy", Locale.UK).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val formatted = utcFormatter.format(Date(millis))
                        fieldValue = TextFieldValue(formatted, selection = TextRange(formatted.length))
                    }
                    showCalendar = false
                }) { Text(tr("Εντάξει")) }
            },
            dismissButton = {
                TextButton(onClick = { showCalendar = false }) { Text(tr("Άκυρο")) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Keeps only digits (max 8) and re-inserts the day-first slashes as the parent types. */
private fun formatDmyDigits(input: String): String {
    val digits = input.filter { it.isDigit() }.take(8)
    return buildString {
        for (i in digits.indices) {
            if (i == 2 || i == 4) append('/')
            append(digits[i])
        }
    }
}

private fun parseDmyDate(input: String, formatter: SimpleDateFormat): Long? {
    if (!Regex("""\d{2}/\d{2}/\d{4}""").matches(input)) return null
    return runCatching {
        formatter.parse(input)?.takeIf { formatter.format(it) == input }?.time
    }.getOrNull()
}
