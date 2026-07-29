package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.ui.i18n.tr
import java.util.Calendar
import kotlinx.coroutines.delay

/** Recomputes at each local midnight so "Today"/"Yesterday" labels stay correct overnight. */
@Composable
fun rememberMidnightTick(): Long {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            tick = now
            val start = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val next = Calendar.getInstance().apply {
                timeInMillis = start
                add(Calendar.DATE, 1)
            }.timeInMillis
            delay((next - System.currentTimeMillis()).coerceAtLeast(1_000L))
        }
    }
    return tick
}

/** Display a local time in 24-hour form, e.g. `15:05`. */
fun formatTime24(millis: Long): String {
    val t = ClockTime24.fromMillis(millis)
    return "%02d:%02d".format(t.hour24, t.minute)
}

/** Display an hour-only 24-hour clock, e.g. reminder slots. */
fun formatHour24(hour24: Int): String = "%02d:00".format(hour24.coerceIn(0, 23))

/** Display a closed hour range in 24-hour form. */
fun formatHourRange24(startHour24: Int, endHour24: Int): String =
    "${formatHour24(startHour24)}–${formatHour24(endHour24)}"

/**
 * 24-hour clock time. [hour24] must be 0..23 and [minute] 0..59 for [isValid];
 * use -1 as an invalid sentinel while typing.
 */
data class ClockTime24(
    val hour24: Int,
    val minute: Int,
) {
    val isValid: Boolean get() = hour24 in 0..23 && minute in 0..59

    companion object {
        fun fromHour24(hour24: Int, minute: Int): ClockTime24 =
            ClockTime24(hour24.coerceIn(0, 23), minute.coerceIn(0, 59))

        fun fromMillis(millis: Long): ClockTime24 {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return fromHour24(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
    }
}

/** Combines a local midnight [dayStart] with a 24-hour clock time. */
fun atDayTime(dayStart: Long, time: ClockTime24): Long =
    Calendar.getInstance().apply {
        timeInMillis = dayStart
        set(Calendar.HOUR_OF_DAY, time.hour24)
        set(Calendar.MINUTE, time.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * 24-hour hour + minutes. Clearing a field marks the clock invalid so callers can disable Save.
 */
@Composable
fun Time24Row(
    label: String,
    time: ClockTime24,
    onTimeChange: (ClockTime24) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hourText by remember { mutableStateOf(if (time.hour24 in 0..23) time.hour24.toString().padStart(2, '0') else "") }
    var minuteText by remember {
        mutableStateOf(if (time.minute in 0..59) time.minute.toString().padStart(2, '0') else "")
    }
    LaunchedEffect(time.hour24, time.minute) {
        if (time.hour24 in 0..23 && hourText.toIntOrNull() != time.hour24) {
            hourText = time.hour24.toString().padStart(2, '0')
        }
        if (time.minute in 0..59 && minuteText.toIntOrNull() != time.minute) {
            minuteText = time.minute.toString().padStart(2, '0')
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = hourText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    hourText = digits
                    val h = digits.toIntOrNull()
                    if (h != null && h in 0..23) {
                        onTimeChange(time.copy(hour24 = h))
                    } else {
                        onTimeChange(time.copy(hour24 = -1))
                    }
                },
                label = { Text(tr("Ώρα")) },
                placeholder = { Text("0–23") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
                singleLine = true,
                isError = hourText.isBlank() || hourText.toIntOrNull()?.let { it !in 0..23 } == true,
            )
            OutlinedTextField(
                value = minuteText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    minuteText = digits
                    val m = digits.toIntOrNull()
                    if (m != null && m in 0..59) {
                        onTimeChange(time.copy(minute = m))
                    } else {
                        onTimeChange(time.copy(minute = -1))
                    }
                },
                label = { Text(tr("Λεπτά")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
                singleLine = true,
                isError = minuteText.isBlank() || minuteText.toIntOrNull()?.let { it !in 0..59 } == true,
            )
        }
    }
}
