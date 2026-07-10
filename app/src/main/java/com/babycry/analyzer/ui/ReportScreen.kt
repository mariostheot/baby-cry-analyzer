package com.babycry.analyzer.ui

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows the generated HTML report right inside the app (a WebView), so tapping "Report"
 * always displays something nice immediately. A button hands the same page to the Android
 * print pipeline, where the user can Save as PDF or share it.
 */
@Composable
fun ReportScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var html by remember { mutableStateOf<String?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) { html = viewModel.exportReportHtml() }

    Column(modifier.fillMaxSize()) {
        val h = html
        if (h == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { webRef?.let { printReport(context, it) } }) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Αποθήκευση / Κοινοποίηση PDF")
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        webRef = this
                    }
                },
                update = { it.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun printReport(context: Context, web: WebView) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = web.createPrintDocumentAdapter("baby-cry-report")
    printManager.print(
        "Γιατί Κλαίει; — Αναφορά",
        adapter,
        PrintAttributes.Builder().build(),
    )
}
