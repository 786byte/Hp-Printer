@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.justprintit.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justprintit.app.scan.ScanViewModel
import com.justprintit.core.scan.EsclClient
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(modifier: Modifier, vm: ScanViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let(vm::savePdf) }
    val saveJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { it?.let(vm::saveSingleJpg) }
    val saveJpgFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::saveJpgs) }
    var copies by remember { mutableIntStateOf(1) }
    var copyMono by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JobProgressCard()
        Text("Scan from the glass", style = MaterialTheme.typography.titleLarge)
        Text("Lift the lid, put the page face down in the corner marked with the arrow, close the lid.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionCard("Settings") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(EsclClient.Region.A4, EsclClient.Region.LETTER, EsclClient.Region.PHOTO_4X6).forEach { r ->
                    FilterChip(selected = s.region == r, onClick = { vm.setRegion(r) }, label = { Text(r.label) })
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = s.color, onClick = { vm.setColor(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Colour") }
                SegmentedButton(selected = !s.color, onClick = { vm.setColor(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Grey") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(150 to "150 dpi", 300 to "300 dpi", 600 to "600 dpi").forEachIndexed { i, (d, l) ->
                    SegmentedButton(selected = s.dpi == d, onClick = { vm.setDpi(d) }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                }
            }
            Text("300 dpi is right for documents; 600 dpi for photos you want to enlarge.", style = MaterialTheme.typography.bodySmall)
        }

        if (s.scanning) {
            Column {
                Text("Scanning…")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancelScan) { Text("Cancel") }
            }
        } else {
            Button(onClick = { vm.scanPage() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Outlined.DocumentScanner, null); Spacer(Modifier.width(8.dp))
                Text(if (s.pages.isEmpty()) "Scan" else "Scan another page")
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        if (s.pages.isNotEmpty()) {
            Text("${s.pages.size} page${if (s.pages.size == 1) "" else "s"} scanned", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.pages.forEachIndexed { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(96.dp).height(130.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Image(p.thumbnail.asImageBitmap(), "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { vm.removePage(p) }) { Icon(Icons.Outlined.Delete, "Remove page ${i + 1}") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { savePdf.launch(vm.suggestedName("pdf")) }, Modifier.weight(1f)) { Text("Save PDF") }
                FilledTonalButton(onClick = {
                    if (s.pages.size == 1) saveJpg.launch(vm.suggestedName("jpg")) else saveJpgFolder.launch(null)
                }, Modifier.weight(1f)) { Text(if (s.pages.size == 1) "Save JPG" else "Save JPGs") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = vm.pdfForSharing() ?: return@launch
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share scan"))
                    }
                }, Modifier.weight(1f)) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
                OutlinedButton(onClick = { vm.printScans(1, false) }, Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Print, null); Spacer(Modifier.width(6.dp)); Text("Print")
                }
            }
            TextButton(onClick = vm::clear) { Text("Start over") }
        }

        SectionCard("Copy (scan + print)") {
            Text("Makes a copy of what's on the glass, at actual size.", style = MaterialTheme.typography.bodySmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !copyMono, onClick = { copyMono = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Colour") }
                SegmentedButton(selected = copyMono, onClick = { copyMono = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Black & white") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Copies", Modifier.weight(1f))
                OutlinedButton(onClick = { copies = (copies - 1).coerceAtLeast(1) }) { Text("−") }
                Text("$copies", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { copies = (copies + 1).coerceAtMost(30) }) { Text("+") }
            }
            Button(onClick = { vm.copyNow(copies, copyMono) }, enabled = !s.scanning, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
