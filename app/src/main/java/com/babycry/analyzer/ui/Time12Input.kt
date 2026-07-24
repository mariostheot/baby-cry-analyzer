package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
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
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
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

/** Localized π.μ./μ.μ. or AM/PM. */
fun periodLabel(isPm: Boolean, lang: AppLang = currentAppLang): String = when (lang) {
    AppLang.EN -> if (isPm) "PM" else "AM"
    AppLang.EL -> if (isPm) "μ.μ." else "π.μ."
}

/** Display time as 12-hour with period, e.g. `3:05 μ.μ.` / `3:05 PM`. */
fun formatTime12(millis: Long, lang: AppLang = currentAppLang): String {
    val t = ClockTime12.fromMillis(millis)
    return "%d:%02d %s".format(t.hour12, t.minute, periodLabel(t.isPm, lang))
}

/** Display an hour-only clock (minutes zero), e.g. reminder slots. */
fun formatHour12(hour24: Int, lang: AppLang = currentAppLang): String {
    val t = ClockTime12.fromHour24(hour24, 0)
    return "%d:00 %s".format(t.hour12, periodLabel(t.isPm, lang))
}

/** Display a closed hour range in 12-hour form. */
fun formatHourRange12(startHour24: Int, endHour24: Int, lang: AppLang = currentAppLang): String {
    val a = ClockTime12.fromHour24(startHour24, 0)
    val b = ClockTime12.fromHour24(endHour24, 0)
    return "%d:00 %s–%d:00 %s".format(
        a.hour12, periodLabel(a.isPm, lang),
        b.hour12, periodLabel(b.isPm, lang),
    )
}

/**
 * 12-hour clock time with π.μ./μ.μ. (AM/PM).
 * [hour12] must be 1..12 and [minute] 0..59 for [isValid]; use 0 / -1 as invalid sentinels while typing.
 */
data class ClockTime12(
    val hour12: Int,
    val minute: Int,
    val isPm: Boolean,
) {
    val isValid: Boolean get() = hour12 in 1..12 && minute in 0..59

    /** 0..23 hour for Calendar.HOUR_OF_DAY. Only meaningful when [isValid]. */
    val hour24: Int
        get() = when {
            hour12 == 12 && !isPm -> 0
            hour12 == 12 && isPm -> 12
            isPm -> hour12 + 12
            else -> hour12
        }

    companion object {
        fun fromHour24(hour24: Int, minute: Int): ClockTime12 {
            val h = hour24.coerceIn(0, 23)
            val m = minute.coerceIn(0, 59)
            return when {
                h == 0 -> ClockTime12(12, m, isPm = false)
                h == 12 -> ClockTime12(12, m, isPm = true)
                h > 12 -> ClockTime12(h - 12, m, isPm = true)
                else -> ClockTime12(h, m, isPm = false)
            }
        }

        fun fromMillis(millis: Long): ClockTime12 {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return fromHour24(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
    }
}

/** Combines a local midnight [dayStart] with a 12-hour clock time. */
fun atDayTime(dayStart: Long, time: ClockTime12): Long =
    Calendar.getInstance().apply {
        timeInMillis = dayStart
        set(Calendar.HOUR_OF_DAY, time.hour24)
        set(Calendar.MINUTE, time.minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Hour (1–12) + minutes + π.μ./μ.μ. chips. Digits-only fields; period toggles via chips.
 * Clearing a field marks the clock invalid so callers can disable Save.
 */
@Composable
fun Time12Row(
    label: String,
    time: ClockTime12,
    onTimeChange: (ClockTime12) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hourText by remember { mutableStateOf(if (time.hour12 in 1..12) time.hour12.toString() else "") }
    var minuteText by remember {
        mutableStateOf(if (time.minute in 0..59) time.minute.toString().padStart(2, '0') else "")
    }
    LaunchedEffect(time.hour12, time.minute) {
        if (time.hour12 in 1..12 && hourText.toIntOrNull() != time.hour12) {
            hourText = time.hour12.toString()
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
                    if (h != null && h in 1..12) {
                        onTimeChange(time.copy(hour12 = h))
                    } else {
                        onTimeChange(time.copy(hour12 = 0))
                    }
                },
                label = { Text(tr("Ώρα")) },
                placeholder = { Text("1–12") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(88.dp),
                singleLine = true,
                isError = hourText.isBlank() || hourText.toIntOrNull()?.let { it !in 1..12 } == true,
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
            FilterChip(
                selected = !time.isPm,
                onClick = { onTimeChange(time.copy(isPm = false)) },
                label = { Text(tr("π.μ.")) },
            )
            FilterChip(
                selected = time.isPm,
                onClick = { onTimeChange(time.copy(isPm = true)) },
                label = { Text(tr("μ.μ.")) },
            )
        }
    }
}
