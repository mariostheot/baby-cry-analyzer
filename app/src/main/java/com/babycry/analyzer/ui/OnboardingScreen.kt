package com.babycry.analyzer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.model.BabyGender
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OnboardingScreen(
    onFinish: (
        name: String,
        birthMillis: Long?,
        colicConfirmed: Boolean,
        gender: BabyGender,
        weightKg: Double?,
        heightCm: Double?,
    ) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf<Long?>(null) }
    var weightKg by remember { mutableStateOf("") }
    var heightCm by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(BabyGender.UNKNOWN) }
    var colic by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    // Birth weight/height are logged with the birth date; without it they would be discarded.
    var showBirthRequiredHint by remember { mutableStateOf(false) }
    val dateFmt = remember(currentAppLang) {
        SimpleDateFormat("dd/MM/yyyy", if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"))
    }
    val wantsBirthMeasurement = weightKg.isNotBlank() || heightCm.isNotBlank()

    // Onboarding is shown BEFORE the Scaffold, so nothing else paints a background here. The
    // platform window is hardcoded white, so without this Surface the theme's near-white
    // onSurface text (in dark mode) landed on white and was invisible. Painting the themed
    // background guarantees the name field + labels always contrast correctly in both modes.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("👶", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            tr("Καλώς ήρθες!"),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Συμπλήρωσε τα στοιχεία του μωρού σου. Η ηλικία βοηθά την εφαρμογή να εκτιμά καλύτερα την αιτία του κλάματος (π.χ. πόσο συχνά πεινά ανάλογα με την ηλικία). Μπορείς να τα αλλάξεις όποτε θες από τις Ρυθμίσεις."),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )

        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(tr("Όνομα μωρού")) },
            singleLine = true,
            // Force strong, readable text/label. The default M3 roles rendered as a very
            // soft lavender that was hard to read on the light background.
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Ημερομηνία γέννησης"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    birth?.let { dateFmt.format(Date(it)) } ?: tr("Δεν έχει οριστεί"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
            OutlinedButton(onClick = { showPicker = true }) { Text(tr("Επιλογή")) }
        }
        if (showBirthRequiredHint && birth == null && wantsBirthMeasurement) {
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Για να αποθηκευτεί το βάρος/ύψος γέννησης χρειάζεται η ημερομηνία γέννησης."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = weightKg,
            onValueChange = { weightKg = sanitizeDecimalMeasurementInput(it) },
            label = { Text(tr("Βάρος (kg)")) },
            supportingText = { Text(tr("Προαιρετικό — βάρος γέννησης σε κιλά (π.χ. 3,4).")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = heightCm,
            onValueChange = { heightCm = sanitizeDecimalMeasurementInput(it) },
            label = { Text(tr("Ύψος (cm)")) },
            supportingText = { Text(tr("Προαιρετικό — μήκος γέννησης σε εκατοστά (π.χ. 50,5).")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                tr("Φύλο"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            GenderOptions(selected = gender, onSelect = { gender = it })
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Επιβεβαιωμένοι κολικοί/αέρια από γιατρό"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    tr("Προαιρετικό — μόνο αν το έχει πει ο παιδίατρος. Δίνει μεγαλύτερο βάρος στο κοιλόπονο/αέρια. Αλλάζει όποτε θες από τις Ρυθμίσεις."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = colic, onCheckedChange = { colic = it })
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (wantsBirthMeasurement && birth == null) {
                    showBirthRequiredHint = true
                    return@Button
                }
                showBirthRequiredHint = false
                val parsedKg = weightKg.trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?.takeIf { value ->
                        kotlin.math.round(value * 1000).toInt() in 1..30_000
                    }
                val parsedHeightCm = heightCm.trim()
                    .replace(',', '.')
                    .toDoubleOrNull()
                    ?.takeIf { value ->
                        kotlin.math.round(value * 10).toInt() in 1..1499
                    }
                onFinish(name, birth, colic, gender, parsedKg, parsedHeightCm)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Ξεκίνα")) }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Θα το κάνω αργότερα"))
        }
    }
    }

    if (showPicker) {
        DmyDateInputDialog(
            initialDateMillis = birth,
            onDismiss = { showPicker = false },
            onConfirm = {
                showBirthRequiredHint = false
                birth = it
                showPicker = false
            },
        )
    }
}

@Composable
private fun GenderOptions(selected: BabyGender, onSelect: (BabyGender) -> Unit) {
    listOf(
        BabyGender.UNKNOWN to tr("Δεν έχει οριστεί"),
        BabyGender.BOY to tr("Αγόρι"),
        BabyGender.GIRL to tr("Κορίτσι"),
    ).forEach { (value, label) ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
