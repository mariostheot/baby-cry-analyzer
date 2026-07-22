package com.babycry.analyzer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.tr
import java.io.File

/**
 * Shows the generated HTML report right inside the app (a WebView), so tapping "Report"
 * always displays something nice immediately. From here the parent can save the same page as a
 * PDF via the Android print pipeline, or share it directly (e.g. send to the pediatrician).
 */
@Composable
fun ReportScreen(viewModel: CryViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var html by remember { mutableStateOf<String?>(null) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val language by viewModel.language.collectAsState()
    val profile by viewModel.profile.collectAsState()

    LaunchedEffect(language, profile.id, profile.name, profile.birthMillis, profile.gender) {
        html = null
        loadedHtml = null
        pageReady = false
        html = viewModel.exportReportHtml()
    }

    Column(modifier.fillMaxSize()) {
        val h = html
        if (h == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val actionsEnabled = webRef != null && pageReady && loadedHtml == h && !sharing
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = { webRef?.let { printReport(context, it) } },
                    enabled = actionsEnabled,
                ) {
                    Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(tr("Αποθήκευση PDF"))
                }
                Button(
                    onClick = {
                        val web = webRef ?: return@Button
                        sharing = true
                        shareReportPdf(
                            context = context,
                            web = web,
                            subject = reportSubject(),
                            chooserTitle = tr("Κοινοποίηση αναφοράς"),
                        ) { ok ->
                            sharing = false
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    tr("Δεν ήταν δυνατή η δημιουργία του PDF. Δοκίμασε ξανά."),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    enabled = actionsEnabled,
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(tr("Κοινοποίηση"))
                }
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageReady = true
                            }
                        }
                        webRef = this
                    }
                },
                update = {
                    if (loadedHtml != h) {
                        pageReady = false
                        it.loadDataWithBaseURL(null, h, "text/html", "UTF-8", null)
                        loadedHtml = h
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun reportSubject(): String = when (currentAppLang) {
    AppLang.EN -> "NiniSense — Report"
    AppLang.EL -> "NiniSense — Αναφορά"
}

private fun printReport(context: Context, web: WebView) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val adapter = web.createPrintDocumentAdapter("baby-cry-report")
    printManager.print(
        reportSubject(),
        adapter,
        PrintAttributes.Builder().build(),
    )
}

/**
 * Renders the report WebView to a PDF file in the cache and opens the Android share sheet so the
 * parent can send it straight to a pediatrician (email, messaging, Drive, etc.). [onResult] is
 * invoked on the main thread with success/failure.
 */
private fun shareReportPdf(
    context: Context,
    web: WebView,
    subject: String,
    chooserTitle: String,
    onResult: (Boolean) -> Unit,
) {
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()
    val adapter = web.createPrintDocumentAdapter("NiniSense-report")
    val dir = File(context.cacheDir, "reports")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "NiniSense-report.pdf")

    adapter.onLayout(
        null,
        attributes,
        null,
        object : PrintDocumentAdapter.LayoutResultCallback() {
            override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                val pfd = openForWrite(file)
                if (pfd == null) {
                    onResult(false)
                    return
                }
                adapter.onWrite(
                    arrayOf(PageRange.ALL_PAGES),
                    pfd,
                    CancellationSignal(),
                    object : PrintDocumentAdapter.WriteResultCallback() {
                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                            runCatching { pfd.close() }
                            val ok = runCatching {
                                launchShare(context, file, subject, chooserTitle)
                            }.isSuccess
                            onResult(ok)
                        }

                        override fun onWriteFailed(error: CharSequence?) {
                            runCatching { pfd.close() }
                            onResult(false)
                        }
                    },
                )
            }

            override fun onLayoutFailed(error: CharSequence?) {
                onResult(false)
            }
        },
        Bundle(),
    )
}

private fun openForWrite(file: File): ParcelFileDescriptor? = try {
    if (file.exists()) file.delete()
    ParcelFileDescriptor.open(
        file,
        ParcelFileDescriptor.MODE_READ_WRITE or
            ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE,
    )
} catch (_: Exception) {
    null
}

private fun launchShare(context: Context, file: File, subject: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}
