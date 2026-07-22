package com.babycry.analyzer.ui

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
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
import java.io.FileOutputStream

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
            val shareChooserTitle = tr("Κοινοποίηση αναφοράς")
            val pdfFailedMessage = tr("Δεν ήταν δυνατή η δημιουργία του PDF. Δοκίμασε ξανά.")
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
                        sharing = true
                        shareReportPdf(
                            context = context,
                            html = h,
                            subject = reportSubject(),
                            chooserTitle = shareChooserTitle,
                        ) { ok ->
                            sharing = false
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    pdfFailedMessage,
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
 * Renders the report HTML to a PDF file in the cache and opens the Android share sheet so the
 * parent can send it straight to a pediatrician (email, messaging, Drive, etc.).
 *
 * We load the HTML into a fresh off-screen [WebView] and draw it onto a paginated [PdfDocument].
 * The print-framework adapter (`onLayout`/`onWrite`) is intentionally avoided because its callback
 * classes have package-private constructors and cannot be subclassed from app code. All work runs
 * on the main thread (WebView requires it); [onResult] is invoked there with success/failure.
 */
private fun shareReportPdf(
    context: Context,
    html: String,
    subject: String,
    chooserTitle: String,
    onResult: (Boolean) -> Unit,
) {
    val web = WebView(context)
    web.settings.javaScriptEnabled = false
    web.webViewClient = object : WebViewClient() {
        private var handled = false
        override fun onPageFinished(view: WebView, url: String?) {
            if (handled) return
            handled = true
            // Let layout settle before measuring/drawing the full content height.
            view.postDelayed({
                val file = runCatching { renderWebViewToPdf(context, view) }.getOrNull()
                if (file == null) {
                    onResult(false)
                    return@postDelayed
                }
                val ok = runCatching { launchShare(context, file, subject, chooserTitle) }.isSuccess
                onResult(ok)
            }, 250)
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}

/** A4 page in points at 72 dpi (595 x 842). */
private const val PDF_PAGE_WIDTH = 595
private const val PDF_PAGE_HEIGHT = 842

private fun renderWebViewToPdf(context: Context, web: WebView): File {
    web.measure(
        View.MeasureSpec.makeMeasureSpec(PDF_PAGE_WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
    )
    val totalHeight = web.measuredHeight.coerceAtLeast(PDF_PAGE_HEIGHT)
    web.layout(0, 0, PDF_PAGE_WIDTH, totalHeight)

    val document = PdfDocument()
    try {
        var top = 0
        var pageNumber = 1
        while (top < totalHeight) {
            val pageInfo = PdfDocument.PageInfo
                .Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, pageNumber)
                .build()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.save()
            canvas.translate(0f, -top.toFloat())
            web.draw(canvas)
            canvas.restore()
            document.finishPage(page)
            top += PDF_PAGE_HEIGHT
            pageNumber++
        }
        val dir = File(context.cacheDir, "reports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "NiniSense-report.pdf")
        if (file.exists()) file.delete()
        FileOutputStream(file).use { document.writeTo(it) }
        return file
    } finally {
        document.close()
    }
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
