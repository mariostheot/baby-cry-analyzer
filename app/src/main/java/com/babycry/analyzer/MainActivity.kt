package com.babycry.analyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.babycry.analyzer.ui.AboutScreen
import com.babycry.analyzer.ui.CryViewModel
import com.babycry.analyzer.ui.HistoryScreen
import com.babycry.analyzer.ui.HomeScreen
import com.babycry.analyzer.ui.LibraryScreen
import com.babycry.analyzer.ui.OnboardingScreen
import com.babycry.analyzer.ui.ReportScreen
import com.babycry.analyzer.ui.SafetyScreen
import com.babycry.analyzer.ui.SettingsScreen
import com.babycry.analyzer.ui.SootheScreen
import com.babycry.analyzer.ui.StatsScreen
import com.babycry.analyzer.ui.theme.BabyCryTheme
import com.babycry.analyzer.notify.ConfirmReminder
import com.babycry.analyzer.notify.FeedReminder
import com.babycry.analyzer.ui.i18n.LocalAppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.tr
import com.babycry.analyzer.ui.i18n.trS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            BabyCryTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Αρχική", Icons.Filled.Mic),
    HISTORY("Ιστορικό", Icons.Filled.History),
    STATS("Στατιστικά", Icons.Filled.Insights),
}

private enum class Overlay(val title: String) {
    SETTINGS("Ρυθμίσεις"),
    ABOUT("Σχετικά"),
    REPORT("Αναφορά"),
    SOOTHE("Ηρέμησε το μωρό"),
    SAFETY("Πότε να ανησυχήσεις"),
    LIBRARY("Αποθηκευμένα κλάματα"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val viewModel: CryViewModel = viewModel()
    val lang by viewModel.language.collectAsState()
    CompositionLocalProvider(LocalAppLang provides lang) {
        AppRootContent(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRootContent(viewModel: CryViewModel) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val home by viewModel.home.collectAsState()
    val onboardingDone by viewModel.onboardingComplete.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.onListenTapped()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(trS("Χρειάζεται άδεια μικροφώνου για την ηχογράφηση."))
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = viewModel.exportBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    }
                }
                snackbarHostState.showSnackbar(trS("Το backup αποθηκεύτηκε."))
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }
                if (json != null) viewModel.importBackup(json)
            }
        }
    }

    val datasetLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        viewModel.writeDatasetZip(out)
                    } ?: 0
                }
                snackbarHostState.showSnackbar(
                    if (count > 0) datasetExportedMessage(count)
                    else trS("Δεν υπάρχουν ακόμη επιβεβαιωμένες ηχογραφήσεις για εξαγωγή."),
                )
            }
        }
    }

    // Android 13+ needs runtime consent before we can post the delayed "why did it cry?"
    // reminder. If denied, the in-app banner still covers it.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(onboardingDone) {
        if (!onboardingDone) return@LaunchedEffect
        ConfirmReminder.ensureChannel(context)
        FeedReminder.ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.refreshPending()
        viewModel.scheduleFeedReminder()
    }

    val onListen: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.onListenTapped()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(home.message) {
        home.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = overlay != null) { overlay = null }

    if (!onboardingDone) {
        OnboardingScreen(
            onFinish = { name, birth -> viewModel.completeOnboarding(name, birth) },
            onSkip = { viewModel.skipOnboarding() },
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(overlay?.let { tr(it.title) } ?: tr("Γιατί Κλαίει;")) },
                navigationIcon = {
                    if (overlay != null) {
                        IconButton(onClick = { overlay = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Πίσω"))
                        }
                    }
                },
                actions = {
                    if (overlay == null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = tr("Μενού"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Ηρέμησε το μωρό")) },
                                leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                                onClick = { menuOpen = false; overlay = Overlay.SOOTHE },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Πότε να ανησυχήσεις")) },
                                leadingIcon = { Icon(Icons.Filled.HealthAndSafety, contentDescription = null) },
                                onClick = { menuOpen = false; overlay = Overlay.SAFETY },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Αποθηκευμένα κλάματα")) },
                                leadingIcon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                                onClick = { menuOpen = false; overlay = Overlay.LIBRARY },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Ρυθμίσεις")) },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; overlay = Overlay.SETTINGS },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Σχετικά")) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = { menuOpen = false; overlay = Overlay.ABOUT },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = overlay == null && tab == entry,
                        onClick = { overlay = null; tab = entry },
                        icon = { Icon(entry.icon, contentDescription = tr(entry.label)) },
                        label = { Text(tr(entry.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            when {
                overlay == Overlay.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    onExportReport = { overlay = Overlay.REPORT },
                    onBackup = { backupLauncher.launch("baby-cry-backup.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json")) },
                    onExportDataset = { datasetLauncher.launch("baby-cry-dataset.zip") },
                )
                overlay == Overlay.ABOUT -> AboutScreen()
                overlay == Overlay.REPORT -> ReportScreen(viewModel)
                overlay == Overlay.SOOTHE -> SootheScreen(viewModel)
                overlay == Overlay.SAFETY -> SafetyScreen()
                overlay == Overlay.LIBRARY -> LibraryScreen(viewModel)
                tab == Tab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onListen = onListen,
                    onCancel = { viewModel.cancelListening() },
                    onSoothe = { overlay = Overlay.SOOTHE },
                    onSafety = { overlay = Overlay.SAFETY },
                )
                tab == Tab.HISTORY -> HistoryScreen(viewModel)
                tab == Tab.STATS -> StatsScreen(viewModel)
            }
        }
    }
}

private fun datasetExportedMessage(count: Int): String = when (currentAppLang) {
    AppLang.EN -> "Exported $count recordings (+ labels.csv)."
    AppLang.EL -> "Εξήχθησαν $count ηχογραφήσεις (+ labels.csv)."
}
