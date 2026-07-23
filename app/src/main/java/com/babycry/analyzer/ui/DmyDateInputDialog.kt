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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manual birth-date input with a device-independent day-first format. Digits are auto-slashed
 * (so the numeric keyboard needs no "/" key) and a calendar button opens a tap-to-pick grid.
 *
 * Storage uses local-midnight millis of the chosen calendar day. The Material DatePicker speaks
 * UTC midnight, so we convert at the picker boundary — otherwise confirming without changing the
 * selection can shift the date by one day east of UTC (e.g. Greece).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmyDateInputDialog(
    initialDateMillis: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    title: String? = null,
) {
    // Local-timezone formatter: typed text and saved millis stay on the device's calendar day.
    val formatter = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.UK).apply { isLenient = false }
    }
    // UTC formatter: Material DatePicker reports UTC midnight for the selected civil day.
    val utcFormatter = remember {
        SimpleDateFormat("dd/MM/yyyy", Locale.UK).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
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
        val todayUtcMidnight = remember { localCalendarDayToUtcMidnight(System.currentTimeMillis()) }
        val pickerInitial = remember(selectedMillis, initialDateMillis) {
            val local = selectedMillis ?: initialDateMillis
            local?.let { localCalendarDayToUtcMidnight(it) }
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickerInitial,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= todayUtcMidnight
            },
        )
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { utcMillis ->
                        // Format the picker's UTC midnight as a civil day, then re-parse with the
                        // local formatter so storage stays local-midnight of that same day.
                        val formatted = utcFormatter.format(Date(utcMillis))
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

/**
 * Converts any instant to UTC midnight of the same local calendar day, which is what
 * [androidx.compose.material3.DatePicker] expects for [initialSelectedDateMillis].
 */
private fun localCalendarDayToUtcMidnight(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(
            local.get(Calendar.YEAR),
            local.get(Calendar.MONTH),
            local.get(Calendar.DAY_OF_MONTH),
        )
    }.timeInMillis
}
