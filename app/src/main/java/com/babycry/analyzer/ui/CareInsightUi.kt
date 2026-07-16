package com.babycry.analyzer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.babycry.analyzer.insights.CareInsight
import com.babycry.analyzer.insights.CareInsightCopy
import com.babycry.analyzer.insights.CareInsightSummary
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr

/**
 * Optional Home card: one calm high-confidence observation. Hidden when [primaryInsight] is null.
 */
@Composable
fun HomeCareInsightCard(
    primaryInsight: CareInsight,
    disclaimer: String,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenStats),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                tr("Από τις καταγραφές σου"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                primaryInsight.homeSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                disclaimer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            TextButton(
                onClick = onOpenStats,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(tr("Δες περισσότερα στα Στατιστικά"))
            }
        }
    }
}

/** Grouped care insights for Stats, replacing the legacy cry-only pattern alerts. */
@Composable
fun CareInsightsStatsSection(
    state: CareInsightsUiState,
    modifier: Modifier = Modifier,
) {
    SectionHeader(
        tr("Έξυπνες παρατηρήσεις"),
        tr("Μοτίβα από ύπνο, ταΐσματα, πάνες και κλάματα — παρατηρητικά, όχι ιατρικά."),
    )
    Spacer(Modifier.height(8.dp))
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when {
                state.loading -> Text(tr("Φόρτωση..."), style = MaterialTheme.typography.bodyMedium)
                else -> CareInsightsStatsBody(state.summary)
            }
        }
    }
}

@Composable
private fun CareInsightsStatsBody(summary: CareInsightSummary?) {
    if (summary == null) {
        Caption(tr("Φόρτωση..."))
        return
    }

    if (summary.insights.isEmpty()) {
        Caption(
            summary.insufficientDataMessage
                ?: tr("Δεν υπάρχει ακόμη αρκετό μοτίβο. Με λίγες ακόμη μέρες χρήσης θα εμφανίζονται παρατηρήσεις εδώ."),
        )
        Spacer(Modifier.height(8.dp))
        Caption(summary.disclaimer)
        return
    }

    val lang = currentAppLang
    summary.insights.forEachIndexed { index, insight ->
        if (index > 0) Spacer(Modifier.height(14.dp))
        Text(
            CareInsightCopy.categoryTitle(insight.category, lang),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(insight.statsDetail, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            insight.sampleContext,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Spacer(Modifier.height(12.dp))
    Caption(summary.disclaimer)
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(2.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
}
