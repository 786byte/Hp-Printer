package com.justprintit.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Scanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.print.PrintState
import com.justprintit.app.print.PrintViewModel
import kotlinx.coroutines.flow.StateFlow

private enum class Tab(val label: String, val icon: ImageVector) {
    STATUS("Status", Icons.Outlined.WaterDrop),
    PRINT("Print", Icons.Outlined.Print),
    SCAN("Scan", Icons.Outlined.Scanner),
    COUNTERS("Counters", Icons.Outlined.BarChart),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(printVm: PrintViewModel, sharedSignal: StateFlow<Int>) {
    val app = LocalContext.current.applicationContext as JustPrintItApp
    var tab by rememberSaveable { mutableIntStateOf(if (app.settings.current.printerHost.isBlank()) Tab.SETTINGS.ordinal else Tab.STATUS.ordinal) }
    val shared by sharedSignal.collectAsState()
    LaunchedEffect(shared) { if (shared > 0) tab = Tab.PRINT.ordinal }

    val snackbar = remember { SnackbarHostState() }
    val printState by app.queue.state.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.fillMaxSize().padding(padding)
        when (Tab.entries[tab]) {
            Tab.STATUS -> StatusScreen(mod, onGoToSettings = { tab = Tab.SETTINGS.ordinal })
            Tab.PRINT -> PrintScreen(printVm, mod)
            Tab.SCAN -> ScanScreen(mod)
            Tab.COUNTERS -> CountersScreen(mod)
            Tab.SETTINGS -> SettingsScreen(mod, onPrintTestPage = { printVm.openTestPage(); tab = Tab.PRINT.ordinal })
        }
    }

    // Results that need the user, shown on top of whatever tab is open.
    when (val s = printState) {
        is PrintState.Failed -> FailedDialog(s,
            onRetryAll = { app.queue.retry(s.jobId) },
            onRetryRest = { app.queue.retry(s.jobId, fromSheet = s.printedSheets ?: 0) },
            onDismiss = { app.queue.dismiss() })
        is PrintState.NeedsFlip -> FlipDialog(s, onContinue = { app.queue.printBacks() }, onSkip = { app.queue.skipBacks() })
        is PrintState.Done -> LaunchedEffect(s) {
            snackbar.showSnackbar("Printed “${s.name}” — ${s.pages} page${if (s.pages == 1) "" else "s"}")
            app.queue.dismiss()
        }
        is PrintState.Canceled -> LaunchedEffect(s) {
            snackbar.showSnackbar("Cancelled “${s.name}”")
            app.queue.dismiss()
        }
        else -> Unit
    }
}

/** A failed job never retries by itself — this dialog is the only way it gets sent again. */
@Composable
private fun FailedDialog(s: PrintState.Failed, onRetryAll: () -> Unit, onRetryRest: () -> Unit, onDismiss: () -> Unit) {
    val printed = s.printedSheets ?: 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Printing didn't finish") },
        text = {
            Column {
                Text(s.message)
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        s.printedSheets == null -> "Check the output tray: some pages may have printed."
                        printed > 0 -> "The printer finished $printed of ${s.totalSheets} page${if (s.totalSheets == 1) "" else "s"}."
                        else -> "Nothing was printed."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text("Fix the problem, then choose whether to print again. The app won't retry on its own.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column {
                if (printed in 1 until s.totalSheets) {
                    TextButton(onClick = onRetryRest) { Text("Print the remaining ${s.totalSheets - printed}") }
                }
                TextButton(onClick = onRetryAll) { Text(if (printed > 0) "Print everything again" else "Retry") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Don't retry") } },
    )
}

@Composable
private fun FlipDialog(s: PrintState.NeedsFlip, onContinue: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Step 2 of 2: print the back sides") },
        text = {
            Column {
                Text("The front sides of “${s.name}” are done.")
                Spacer(Modifier.height(12.dp))
                s.instructions.forEachIndexed { i, line ->
                    Text("${i + 1}. $line", Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Pages come out wrong way round? Change it in Settings → Double-sided, and use the test page.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Print ${s.backCount} back side${if (s.backCount == 1) "" else "s"}") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip backs") } },
    )
}
