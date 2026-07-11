package com.babycry.analyzer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/** One row of the age guide. Numbers are language-neutral; the rest are translated. */
private data class TummyGuideRow(
    val age: String,
    val freq: String,
    val perSession: String,
    val total: String,
    val expect: String,
)

private val guideRows = listOf(
    TummyGuideRow(
        age = "Νεογέννητο · 1η εβδομάδα",
        freq = "2–3",
        perSession = "1–3′",
        total = "λίγα λεπτά",
        expect = "Ξεκίνα από την 1η μέρα στο σπίτι — μετράει και η αγκαλιά στο στήθος (tummy-to-chest).",
    ),
    TummyGuideRow(
        age = "Εβδομάδες 2–4",
        freq = "3–5",
        perSession = "3–5′",
        total = "σταδιακή αύξηση",
        expect = "«Λίγο & συχνά» — π.χ. μετά την αλλαγή πάνας ή μόλις ξυπνήσει.",
    ),
    TummyGuideRow(
        age = "~7 εβδομάδες",
        freq = "3–5",
        perSession = "5–10′",
        total = "15–30′",
        expect = "Ορόσημο AAP: περίπου 15–30′ συνολικά μέσα στην ημέρα.",
    ),
    TummyGuideRow(
        age = "Μήνας 2",
        freq = "4–6",
        perSession = "5–10′",
        total = "15–30′",
        expect = "Σηκώνει πιο ψηλά και πιο σταθερά το κεφάλι.",
    ),
    TummyGuideRow(
        age = "Μήνας 3",
        freq = "5–6",
        perSession = "~10′",
        total = "≈60′ (WHO ≥30′)",
        expect = "Στηρίζεται στους πήχεις και κρατά το κεφάλι 45–90°.",
    ),
    TummyGuideRow(
        age = "Μήνες 4–5",
        freq = "4–5",
        perSession = "10–20′",
        total = "≈60′",
        expect = "Αρχίζει να γυρίζει από μπρούμυτα σε ανάσκελα.",
    ),
    TummyGuideRow(
        age = "Μήνας 6+ (μέχρι το μπουσούλημα)",
        freq = "3–4",
        perSession = "όσο αντέχει",
        total = "≥60′",
        expect = "Παιχνίδι, κύλισμα, άπλωμα — συνέχισε μέχρι να μπουσουλήσει.",
    ),
)

@Composable
fun TummyTimeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            tr("Tummy Time"),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            tr("Χρόνος που το μωρό είναι ξύπνιο και ξαπλωμένο μπρούμυτα, υπό επίβλεψη. Δυναμώνει αυχένα, ώμους και κορμό και βοηθά στην κινητική ανάπτυξη (και προλαμβάνει το πλατύ/επίπεδο κεφαλάκι)."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))

        Text(tr("Πρόγραμμα ανά ηλικία"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr("Οι «φορές/ημέρα» είναι ενδεικτικές (σύνολο ÷ διάρκεια ανά φορά)· οι πηγές ορίζουν κυρίως το σύνολο των λεπτών."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        guideRows.forEach { row ->
            AgeGuideCard(row)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    tr("Ασφάλεια & πρακτικά"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                SafetyBullet(tr("Πάντα ξύπνιο και υπό επίβλεψη, σε επίπεδη σταθερή επιφάνεια."))
                SafetyBullet(tr("Ο ύπνος πάντα ανάσκελα (Back to Sleep) — το tummy time είναι μόνο για ξύπνιες ώρες παιχνιδιού."))
                SafetyBullet(tr("Καλή στιγμή: μετά την αλλαγή πάνας ή μόλις ξυπνήσει — όχι με γεμάτο στομάχι."))
                SafetyBullet(tr("Αν δυσανασχετεί, δοκίμασε πλάγια θέση ή ένα ρολό πετσέτας κάτω από τις μασχάλες."))
                SafetyBullet(tr("Σταμάτα αν κουραστεί ή κλάψει· ξαναδοκίμασε αργότερα. «Λίγο & συχνά»."))
                SafetyBullet(tr("Συνέχισε καθημερινά μέχρι να μπουσουλήσει."))
            }
        }
        Spacer(Modifier.height(16.dp))

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    tr("Στην αρχική οθόνη"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tr("Πάτα «Έκανα Tummy Time» κάθε φορά που το κάνεις. Θα βλέπεις πόσες φορές έχεις κάνει σήμερα και πόσες απομένουν για τον στόχο της ηλικίας — και θα σου το θυμίζουμε δύο φορές την ημέρα (πρωί & απόγευμα)."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            tr("Πηγές: AAP/HealthyChildren, Pathways.org, NHS, WHO. Ενημερωτικό βοήθημα, όχι ιατρική συμβουλή."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AgeGuideCard(row: TummyGuideRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    tr(row.age),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    tr(row.total),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${tr("Φορές/μέρα")}: ${row.freq}   ·   ${tr("Διάρκεια/φορά")}: ${tr(row.perSession)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(tr(row.expect), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SafetyBullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("• ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
