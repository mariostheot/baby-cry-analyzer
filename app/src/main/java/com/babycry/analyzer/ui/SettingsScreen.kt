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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.model.BabyGender
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
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
    onExportDataset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by viewModel.profile.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val personalization by viewModel.personalizationEnabled.collectAsState()
    val language by viewModel.language.collectAsState()
    val tummyReminderOn by viewModel.tummyReminderEnabled.collectAsState()
    val tummyReminderHourAm by viewModel.tummyReminderHourAm.collectAsState()
    val tummyReminderHourPm by viewModel.tummyReminderHourPm.collectAsState()
    val lastBackupAt by viewModel.lastBackupAt.collectAsState()
    val focus = LocalFocusManager.current
    val context = LocalContext.current

    var dataset by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var backupRecordings by remember { mutableStateOf(0) }
    LaunchedEffect(profile.id, profiles, events, lastBackupAt) {
        dataset = viewModel.datasetInfo()
        backupRecordings = viewModel.backupRecordingCount()
    }

    var name by remember(profile) { mutableStateOf(profile.name) }
    var birth by remember(profile) { mutableStateOf(profile.birthMillis) }
    var gender by remember(profile) { mutableStateOf(profile.gender) }
    var showPicker by remember { mutableStateOf(false) }
    var justSaved by remember(profile.id) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var deleteBabyId by remember { mutableStateOf<String?>(null) }

    val dateFmt = remember(currentAppLang) {
        SimpleDateFormat("dd/MM/yyyy", if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Ρυθμίσεις"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---- Language ----
        SectionTitle(tr("Γλώσσα"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                listOf(AppLang.EL to "Ελληνικά", AppLang.EN to "English").forEach { (lang, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setLanguage(lang) }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = language == lang,
                            onClick = { viewModel.setLanguage(lang) },
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Babies (multi-profile) ----
        SectionTitle(tr("Μωρά"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                if (profiles.isEmpty()) {
                    Text(
                        tr("Δεν έχει προστεθεί μωρό ακόμη."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(8.dp),
                    )
                }
                profiles.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectBaby(p.id) }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = p.id == profile.id,
                            onClick = { viewModel.selectBaby(p.id) },
                        )
                        Spacer(Modifier.size(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name.ifBlank { tr("Χωρίς όνομα") },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                p.birthMillis?.let { dateFmt.format(Date(it)) } ?: tr("Χωρίς ημ. γέννησης"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (profiles.size > 1) {
                            IconButton(onClick = { deleteBabyId = p.id }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = tr("Διαγραφή μωρού"))
                            }
                        }
                    }
                }
                Divider(Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { viewModel.addBaby() }, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(tr("Προσθήκη μωρού"))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Active baby details ----
        SectionTitle(tr("Στοιχεία ενεργού μωρού"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; justSaved = false },
                    label = { Text(tr("Όνομα")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(tr("Φύλο"), style = MaterialTheme.typography.bodyLarge)
                listOf(
                    BabyGender.UNKNOWN to tr("Δεν έχει οριστεί"),
                    BabyGender.BOY to tr("Αγόρι"),
                    BabyGender.GIRL to tr("Κορίτσι"),
                ).forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                gender = value
                                justSaved = false
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = gender == value,
                            onClick = {
                                gender = value
                                justSaved = false
                            },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Ημ. γέννησης"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            birth?.let { dateFmt.format(Date(it)) } ?: tr("Δεν έχει οριστεί"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    OutlinedButton(onClick = { showPicker = true }) { Text(tr("Επιλογή")) }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        focus.clearFocus()
                        viewModel.saveProfile(name, birth, gender)
                        justSaved = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Αποθήκευση προφίλ")) }
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
                            tr("Αποθηκεύτηκε"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Analysis toggles ----
        SectionTitle(tr("Ανάλυση"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = tr("Μαθαίνει από εσένα"),
                    subtitle = tr("Χρησιμοποιεί τις διορθώσεις σου (προσωποποίηση)"),
                    checked = personalization,
                    onCheckedChange = viewModel::setPersonalization,
                )
                Divider(Modifier.padding(vertical = 12.dp))
                ToggleRow(
                    title = tr("Επιβεβαιωμένοι κολικοί/αέρια από γιατρό"),
                    subtitle = tr("Αν ο παιδίατρος έχει επιβεβαιώσει κολικούς ή αέρια στο μωρό, δίνουμε μεγαλύτερο βάρος στο κοιλόπονο/ρέψιμο — ιδίως ανάμεσα στα γεύματα. Ισχύει για το ενεργό μωρό."),
                    checked = profile.colicConfirmed,
                    onCheckedChange = viewModel::setColicConfirmed,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("Η εφαρμογή λαμβάνει πάντα υπόψη το τελευταίο τάισμα, την ώρα και την ηλικία του μωρού για πιο ακριβή εκτίμηση."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Reminders ----
        SectionTitle(tr("Υπενθυμίσεις"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ToggleRow(
                    title = tr("Υπενθύμιση Tummy Time"),
                    subtitle = tr("Δύο ήπιες ειδοποιήσεις την ημέρα (πρωί & απόγευμα), ανάλογα με την ηλικία, με το πόσες φορές απομένουν."),
                    checked = tummyReminderOn,
                    onCheckedChange = viewModel::setTummyReminderEnabled,
                )
                if (tummyReminderOn) {
                    Divider(Modifier.padding(vertical = 8.dp))
                    ActionRow(
                        Icons.Filled.Schedule,
                        tr("Πρωινή υπενθύμιση"),
                        "%02d:00".format(tummyReminderHourAm),
                    ) {
                        android.app.TimePickerDialog(
                            context,
                            { _, h, _ -> viewModel.setTummyReminderHourAm(h) },
                            tummyReminderHourAm,
                            0,
                            true,
                        ).show()
                    }
                    Divider(Modifier.padding(vertical = 8.dp))
                    ActionRow(
                        Icons.Filled.Schedule,
                        tr("Απογευματινή υπενθύμιση"),
                        "%02d:00".format(tummyReminderHourPm),
                    ) {
                        android.app.TimePickerDialog(
                            context,
                            { _, h, _ -> viewModel.setTummyReminderHourPm(h) },
                            tummyReminderHourPm,
                            0,
                            true,
                        ).show()
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Data ----
        SectionTitle(tr("Δεδομένα"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.Description,
                    tr("Προβολή / Εξαγωγή αναφοράς"),
                    tr("Ανοίγει μια όμορφη αναφορά· από εκεί την αποθηκεύεις ή τη μοιράζεσαι ως PDF"),
                    onExportReport,
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.Backup,
                    tr("Δημιουργία backup"),
                    backupHealthText(lastBackupAt, backupRecordings),
                    onBackup,
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.Restore,
                    tr("Επαναφορά από backup"),
                    tr("Φόρτωση δεδομένων από αρχείο"),
                    onRestore,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Personal dataset (export only; recordings are always saved automatically) ----
        SectionTitle(tr("Δικό μου dataset"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    dataset?.let { (n, bytes) -> datasetInfoText(n, bytes) } ?: tr("Υπολογισμός..."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Divider(Modifier.padding(vertical = 8.dp))
                ActionRow(
                    Icons.Filled.Archive,
                    tr("Εξαγωγή dataset (zip)"),
                    tr("Εξάγει τις επιβεβαιωμένες ηχογραφήσεις + labels.csv για εκπαίδευση. Μένουν στη συσκευή σου."),
                    onExportDataset,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- Personalization maintenance ----
        SectionTitle(tr("Προσωποποίηση (τι έμαθε από εσένα)"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.Refresh,
                    tr("Ανανέωση προσωποποίησης"),
                    tr("Ξαναϋπολογίζει όσα έμαθε από τις διορθώσεις σου. Δεν σβήνει τίποτα."),
                    { viewModel.refreshData() },
                )
                Divider(Modifier.padding(vertical = 4.dp))
                ActionRow(
                    Icons.Filled.RestartAlt,
                    tr("Μηδενισμός προσωποποίησης"),
                    tr("Ξεχνά όσα έμαθε από εσένα. Το ιστορικό & τα στατιστικά ΔΕΝ σβήνονται."),
                    { confirm = Confirm.RESET_PERSONALIZATION },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ---- History / stats ----
        SectionTitle(tr("Ιστορικό & στατιστικά"))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ActionRow(
                    Icons.Filled.DeleteSweep,
                    tr("Καθαρισμός ιστορικού & στατιστικών"),
                    tr("Μηδενίζει κλάματα, ηχογραφήσεις, ταΐσματα, αλλαγές πάνας, tummy time και γραφήματα. Η εκμάθηση παραμένει."),
                    { confirm = Confirm.CLEAR_HISTORY },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showPicker) {
        val todayMs = System.currentTimeMillis()
        val nowYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = birth ?: todayMs,
            // A birth date can't be in the future.
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMs
                override fun isSelectableYear(year: Int): Boolean = year <= nowYear
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    birth = state.selectedDateMillis
                    justSaved = false
                    showPicker = false
                }) { Text(tr("Εντάξει")) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(tr("Άκυρο")) }
            },
        ) {
            DatePicker(state = state)
        }
    }

    confirm?.let { action ->
        val (title, body) = when (action) {
            Confirm.RESET_PERSONALIZATION ->
                tr("Μηδενισμός προσωποποίησης;") to
                    tr("Το μοντέλο θα ξεχάσει όσα έμαθε από τις διορθώσεις σου και θα επιστρέψει στη βασική του κατάσταση. Το ιστορικό & τα στατιστικά μένουν.")
            Confirm.CLEAR_HISTORY ->
                tr("Καθαρισμός ιστορικού;") to
                    tr("Θα διαγραφούν όλα τα καταγεγραμμένα κλάματα (μαζί με τις αποθηκευμένες ηχογραφήσεις), τα ταΐσματα, οι αλλαγές πάνας, το tummy time και τα γραφήματα. Αυτό που έμαθε το μοντέλο από εσένα ΔΕΝ επηρεάζεται.")
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
                }) { Text(tr("Ναι")) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(tr("Άκυρο")) }
            },
        )
    }

    deleteBabyId?.let { id ->
        val p = profiles.firstOrNull { it.id == id }
        val who = p?.name?.takeIf { it.isNotBlank() }?.let { " «$it»" } ?: ""
        AlertDialog(
            onDismissRequest = { deleteBabyId = null },
            title = { Text(tr("Διαγραφή μωρού;")) },
            text = {
                Text(deleteBabyMessage(who))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBaby(id)
                    deleteBabyId = null
                }) { Text(tr("Διαγραφή")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteBabyId = null }) { Text(tr("Άκυρο")) }
            },
        )
    }
}

private fun deleteBabyMessage(who: String): String = when (currentAppLang) {
    AppLang.EN -> "Profile$who will be removed. Its cries, recordings, feedings, diaper changes, tummy time and learning examples will also be deleted."
    AppLang.EL -> "Θα αφαιρεθεί το προφίλ$who. Θα διαγραφούν επίσης τα κλάματα, οι ηχογραφήσεις, τα ταΐσματα, οι πάνες, το tummy time και τα παραδείγματα εκμάθησης του."
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

private fun datasetInfoText(n: Int, bytes: Long): String = when (currentAppLang) {
    AppLang.EN -> "Stored: $n recordings • ${formatBytes(bytes)}"
    AppLang.EL -> "Αποθηκευμένα: $n ηχογραφήσεις • ${formatBytes(bytes)}"
}

private fun backupHealthText(lastBackupAt: Long, recordings: Int): String {
    val last = if (lastBackupAt <= 0L) {
        when (currentAppLang) {
            AppLang.EN -> "never"
            AppLang.EL -> "ποτέ"
        }
    } else {
        val fmt = SimpleDateFormat("d/M HH:mm", if (currentAppLang == AppLang.EN) Locale.ENGLISH else Locale("el"))
        fmt.format(Date(lastBackupAt))
    }
    return when (currentAppLang) {
        AppLang.EN -> "Last backup: $last • includes $recordings recordings"
        AppLang.EL -> "Τελευταίο backup: $last • περιλαμβάνει $recordings ηχογραφήσεις"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
