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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Confirm { RESET_PERSONALIZATION, CLEAR_HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CryViewModel,
    onExportReport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by viewModel.profile.collectAsState()
    val personalization by viewModel.personalizationEnabled.collectAsState()
    val context by viewModel.contextEnabled.collectAsState()
    val focus = LocalFocusManager.current

    var name by remember(profile) { mutableStateOf(profile.name) }
    var birth by remember(profile) { mutableStateOf(profile.birthMillis) }
    var showPicker by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale("el")) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "Ρυθμίσεις",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---- Baby profile ----
        SectionTitle("Το μωρό μου")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; justSaved = false },
                    label = { Text("Όνομα") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ημ. γέννησης", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            birth?.let { dateFmt.format(Date(it)) } ?: "Δεν έχει οριστεί",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    OutlinedButton(onClick = { showPicker = true }) { Text("Επιλογή") }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        viewModel.saveProfile(name, birth)
                        justSaved = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Αποθήκευση προφίλ") }
                if (justSaved) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "Αποθηκεύτηκε",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Analysis toggles ----
        SectionTitle("Ανάλυση")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = "Μαθαίνει από εσένα",
                    subtitle = "Χρησιμοποιεί τις διορθώσεις σου (προσωποποίηση)",
                    checked = personalization,
                    onCheckedChange = viewModel::setPersonalization,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Πλαίσιο (τάισμα/ώρα/ηλικία)",
                    subtitle = "Σταθμίζει με βάση τελευταία σίτιση, ώρα και ηλικία",
                    checked = context,
                    onCheckedChange = viewModel::setContext,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Data ----
        SectionTitle("Δεδομένα")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.Description,
                    "Προβολή / Εξαγωγή αναφοράς",
                    "Ανοίγει μια όμορφη αναφορά· από εκεί την αποθηκεύεις ή τη μοιράζεσαι ως PDF",
                    onExportReport,
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.Backup,
                    "Δημιουργία backup",
                    "Αποθήκευση όλων των δεδομένων σε αρχείο",
                    onBackup,
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.Restore,
                    "Επαναφορά από backup",
                    "Φόρτωση δεδομένων από αρχείο",
                    onRestore,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Personalization maintenance ----
        SectionTitle("Προσωποποίηση (τι έμαθε από εσένα)")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.Refresh,
                    "Ανανέωση προσωποποίησης",
                    "Ξαναϋπολογίζει όσα έμαθε από τις διορθώσεις σου. Δεν σβήνει τίποτα.",
                    { viewModel.refreshData() },
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.RestartAlt,
                    "Μηδενισμός προσωποποίησης",
                    "Ξεχνά όσα έμαθε από εσένα. Το ιστορικό & τα στατιστικά ΔΕΝ σβήνονται.",
                    { confirm = Confirm.RESET_PERSONALIZATION },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- History / stats ----
        SectionTitle("Ιστορικό & στατιστικά")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.DeleteSweep,
                    "Καθαρισμός ιστορικού & στατιστικών",
                    "Μηδενίζει τα καταγεγραμμένα κλάματα, τα γραφήματα και τα ταΐσματα. Η εκμάθηση παραμένει.",
                    { confirm = Confirm.CLEAR_HISTORY },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birth ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    birth = state.selectedDateMillis
                    justSaved = false
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Άκυρο") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    confirm?.let { action ->
        val (title, body) = when (action) {
            Confirm.RESET_PERSONALIZATION ->
                "Μηδενισμός προσωποποίησης;" to
                    "Το μοντέλο θα ξεχάσει όσα έμαθε από τις διορθώσεις σου και θα " +
                    "επιστρέψει στη βασική του κατάσταση. Το ιστορικό & τα στατιστικά μένουν."
            Confirm.CLEAR_HISTORY ->
                "Καθαρισμός ιστορικού;" to
                    "Θα διαγραφούν όλα τα καταγεγραμμένα κλάματα, τα γραφήματα και τα " +
                    "ταΐσματα. Αυτό που έμαθε το μοντέλο από εσένα ΔΕΝ επηρεάζεται."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        Confirm.RESET_PERSONALIZATION -> viewModel.resetPersonalization()
                        Confirm.CLEAR_HISTORY -> viewModel.clearHistory()
                    }
                    confirm = null
                }) { Text("Ναι") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Άκυρο") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
