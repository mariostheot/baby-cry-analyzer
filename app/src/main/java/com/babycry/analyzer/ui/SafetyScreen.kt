package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.ui.i18n.tr

@Composable
fun SafetyScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Πότε να ανησυχήσεις"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            tr("Η εφαρμογή είναι ένα βοηθητικό εργαλείο, ΟΧΙ ιατρική διάγνωση. Αν κάτι σε ανησυχεί, εμπιστέψου το ένστικτό σου και επικοινώνησε με τον παιδίατρό σου."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    tr("Ζήτησε άμεσα βοήθεια (παιδίατρος / ΕΚΑΒ 166) αν:"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                RedFlag(tr("Δυσκολία στην αναπνοή, πολύ γρήγορη/θορυβώδης αναπνοή, ή μπλε/μωβ χείλη & πρόσωπο."))
                RedFlag(tr("Πυρετός σε μωρό κάτω των 3 μηνών (≥38°C), ή επίμονος/πολύ υψηλός πυρετός."))
                RedFlag(tr("Ασταμάτητο, οξύ ή ασυνήθιστο κλάμα που δεν σταματά με τίποτα (πάνω από ~2 ώρες)."))
                RedFlag(tr("Υπερβολική νωθρότητα, δυσκολεύεσαι να το ξυπνήσεις ή είναι «πεσμένο»."))
                RedFlag(tr("Επαναλαμβανόμενοι εμετοί, εμετός πράσινος/με αίμα, ή αίμα στα κόπρανα."))
                RedFlag(tr("Σημάδια αφυδάτωσης: πολύ λίγες βρεγμένες πάνες, στεγνό στόμα, βυθισμένη πηγή."))
                RedFlag(tr("Σπασμοί/τινάγματα, ή εξάνθημα που δεν ασπρίζει όταν το πιέζεις."))
                RedFlag(tr("Πτώση ή χτύπημα στο κεφάλι, ή αν αρνείται εντελώς να φάει."))
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    tr("Πριν από αυτά, συνήθως αρκεί να ελέγξεις:"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Bullet(tr("Πείνα — πότε έφαγε τελευταία φορά;"))
                Bullet(tr("Πάνα — βρεγμένη ή λερωμένη;"))
                Bullet(tr("Θερμοκρασία — μήπως κρυώνει ή ζεσταίνεται; (ένα στρώμα παραπάνω από εσένα)"))
                Bullet(tr("Αέρια/ρέψιμο — κράτησέ το όρθιο μετά το τάισμα."))
                Bullet(tr("Κούραση/υπερδιέγερση — ησυχία, χαμηλό φως, αγκαλιά."))
                Bullet(tr("Ανάγκη για αγκαλιά και επαφή."))
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            tr("Οι πληροφορίες είναι γενικές και βασίζονται σε δημόσιες οδηγίες (τύπου AAP/NHS). Δεν αντικαθιστούν τη γνώμη γιατρού."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RedFlag(text: String) {
    Row2("•", text, MaterialTheme.colorScheme.onErrorContainer)
}

@Composable
private fun Bullet(text: String) {
    Row2("•", text, MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Row2(bullet: String, text: String, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 3.dp)) {
        Text("$bullet ", style = MaterialTheme.typography.bodyMedium, color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
