@file:OptIn(ExperimentalMaterial3Api::class)

package com.justprintit.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.data.FoundPrinter
import com.justprintit.app.print.PrintOptions
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SettingsScreen(modifier: Modifier, onPrintTestPage: () -> Unit) {
    val app = LocalContext.current.applicationContext as JustPrintItApp
    val s by app.settings.values.collectAsState()
    val status by app.printer.status.collectAsState()
    val scope = rememberCoroutineScope()

    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundPrinter>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var manualIp by remember { mutableStateOf(s.printerHost) }
    var testing by remember { mutableStateOf(false) }

    fun search() {
        searching = true; searchError = null
        scope.launch {
            withTimeoutOrNull(12_000) {
                app.printer.discover()
                    .catch { e -> searchError = e.message }
                    .collect { list ->
                        // HP first — it's the printer this app is built around.
                        found = list.sortedByDescending { (it.model ?: it.name).contains("HP", ignoreCase = true) }
                    }
            }
            searching = false
            if (found.isEmpty() && searchError == null) searchError = "No printers found. Check the printer's Wi-Fi light, or type its IP address below (find it in your router's device list or on the printer's network configuration report)."
        }
    }

    LaunchedEffect(Unit) { if (s.printerHost.isBlank()) search() }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        SectionCard("Printer") {
            if (s.printerHost.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Print, null)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(s.printerName.ifBlank { status.info?.makeAndModel ?: "Printer" })
                        Text(s.printerHost, style = MaterialTheme.typography.bodySmall)
                    }
                    if (status.online) Icon(Icons.Outlined.CheckCircle, "Connected", tint = MaterialTheme.colorScheme.primary)
                }
            }
            OutlinedButton(onClick = ::search, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                if (searching) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.padding(4.dp)) }
                Text(if (searching) "Searching the Wi-Fi…" else "Find printers on Wi-Fi")
            }
            found.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        app.settings.update { it.copy(printerHost = p.host, printerPort = p.port, printerPath = p.path, printerName = p.name) }
                        manualIp = p.host
                        scope.launch { app.printer.refresh() }
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text("${p.model ?: ""} · ${p.host}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.host == s.printerHost) Icon(Icons.Outlined.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                }
            }
            searchError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            OutlinedTextField(
                value = manualIp, onValueChange = { manualIp = it.trim() },
                label = { Text("Printer IP address") }, placeholder = { Text("192.168.1.50") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                app.settings.update { it.copy(printerHost = manualIp, printerPort = 631, printerPath = "/ipp/print", printerName = "") }
                testing = true
                scope.launch { app.printer.refresh(); testing = false }
            }, enabled = manualIp.isNotBlank() && !testing) { Text(if (testing) "Connecting…" else "Use this address") }
            status.error?.let { if (s.printerHost.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text("Tip: give the printer a fixed IP address in your router so the app always finds it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Print defaults") {
            PaperPicker(PrintOptions(paper = s.defaultPaper), status.info?.media.orEmpty()) { p -> app.settings.update { it.copy(defaultPaper = p.keyword) } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3 to "Draft", 4 to "Normal", 5 to "Best").forEachIndexed { i, (q, l) ->
                    SegmentedButton(selected = s.defaultQuality == q, onClick = { app.settings.update { it.copy(defaultQuality = q) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            SwitchRow("Black & white by default", "Saves colour ink", s.defaultMono) { v -> app.settings.update { it.copy(defaultMono = v) } }
            OutlinedTextField(
                value = s.userName, onValueChange = { v -> app.settings.update { it.copy(userName = v) } },
                label = { Text("Your name (shown on the printer)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard("Double-sided (guided)") {
            Text("Your printer prints on one side. The app prints the fronts, then you put the stack back in for the backs. " +
                "If backs come out upside down or in the wrong order, change these and print the test page.",
                style = MaterialTheme.typography.bodySmall)
            SwitchRow("Print backs last page first", "So the stack goes back in without re-sorting", s.duplexReverseBacks) { v ->
                app.settings.update { it.copy(duplexReverseBacks = v) }
            }
            SwitchRow("Turn backs upside down", "So you don't have to turn the stack around", s.duplexRotateBacks) { v ->
                app.settings.update { it.copy(duplexRotateBacks = v) }
            }
            SwitchRow("Blank pass for odd page counts", "Feeds the last front through without printing", s.duplexBlankForOdd) { v ->
                app.settings.update { it.copy(duplexBlankForOdd = v) }
            }
            TextButton(onClick = onPrintTestPage) { Text("Print a 2-page test") }
        }

        SectionCard("About") {
            Text("Just Print It 1.0 — made for the HP Ink Tank / Smart Tank 580 series. Works with most Wi-Fi printers that support " +
                "IPP Everywhere / Mopria and eSCL scanning.", style = MaterialTheme.typography.bodySmall)
            Text("Everything stays on your phone and your home network. No account, no cloud.",
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}
