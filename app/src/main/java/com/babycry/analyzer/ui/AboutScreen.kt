package com.babycry.analyzer.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "Έκδοση ${pi.versionName} (build $code)"
        }.getOrDefault("Έκδοση 1.0")
    }
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text("👶🔊", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Γιατί Κλαίει;",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            version,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Δημιουργήθηκε από τον Μάριο Θεοτή",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        InfoCard(
            title = "Τι κάνει",
            body = "Ηχογραφεί το κλάμα του μωρού και εκτιμά την πιθανή αιτία " +
                "(πείνα, κούραση, κοιλόπονος, ρέψιμο, δυσφορία) με ένα μοντέλο AI " +
                "που τρέχει τοπικά στο κινητό. Μαθαίνει από τις διορθώσεις σου.",
        )

        Spacer(Modifier.height(12.dp))
        InfoCard(
            title = "Ιδιωτικότητα",
            body = "Όλα γίνονται τοπικά στη συσκευή. Καμία ηχογράφηση ή δεδομένο δεν " +
                "ανεβαίνει στο διαδίκτυο και δεν μοιράζεται με κανέναν.",
        )

        Spacer(Modifier.height(12.dp))
        InfoCard(
            title = "Τεχνολογία & credits",
            body = "• YAMNet (Google, AudioSet) — εξαγωγή χαρακτηριστικών ήχου\n" +
                "• TensorFlow Lite — inference στο κινητό\n" +
                "• Σύνολα δεδομένων: donateacry-corpus, InfantCry-DBL (Mendeley Data), ESC-50\n" +
                "• Jetpack Compose · Material 3",
        )

        Spacer(Modifier.height(12.dp))
        InfoCard(
            title = "Σημαντική σημείωση",
            body = "Ενημερωτικό βοήθημα, όχι ιατρική συσκευή ή διάγνωση. Για οτιδήποτε " +
                "αφορά την υγεία του μωρού, συμβουλέψου παιδίατρο.",
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "© $year Μάριος Θεοτή",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}
