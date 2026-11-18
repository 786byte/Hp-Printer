@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.justprintit.app.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.data.Paper
import com.justprintit.app.print.PrintOptions
import com.justprintit.app.print.PrintViewModel
import com.justprintit.app.print.ScaleMode
import com.justprintit.core.layout.Imposition
import com.justprintit.core.layout.SidesMode
import kotlin.math.roundToInt

@Composable
fun PrintScreen(vm: PrintViewModel, modifier: Modifier) {
    val state by vm.state.collectAsState()
    val app = LocalContext.current.applicationContext as JustPrintItApp
    val printerInfo by app.printer.status.collectAsState()
    val busy by app.queue.state.collectAsState()

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.open(listOf(u)) } }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.open(it) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        JobProgressCard()

        val doc = state.doc
        if (doc == null) {
            Text("What do you want to print?", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { pickPdf.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f).height(72.dp)) {
                    Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("PDF")
                }
                FilledTonalButton(onClick = { pickPhotos.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f).height(72.dp)) {
                    Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text("Photos")
                }
            }
            Text("Tip: in any app, tap Share → Just Print It.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }

        val o = state.options
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text("${doc.pageCount} ${if (doc.isPhotos) "photo" else "page"}${if (doc.pageCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = vm::close) { Icon(Icons.Outlined.Close, "Close document") }
        }

        // Preview
        val paper = Paper.of(o.paper)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth(0.62f).aspectRatio((paper.widthMm / paper.heightMm).toFloat())
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center,
            ) {
                state.preview?.let { Image(it.asImageBitmap(), "Preview", Modifier.fillMaxWidth()) } ?: CircularProgressIndicator()
            }
            if (state.previewSheets.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.showPreview(state.previewIndex - 1) }, enabled = state.previewIndex > 0) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous")
                    }
                    Text("Sheet ${state.previewIndex + 1} of ${state.previewSheets.size}" +
                        if (o.sides == SidesMode.GUIDED_DUPLEX) (if (state.previewIndex % 2 == 0) " · front" else " · back") else "")
                    IconButton(onClick = { vm.showPreview(state.previewIndex + 1) }, enabled = state.previewIndex < state.previewSheets.size - 1) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next")
                    }
                }
            }
        }

        SectionCard("Pages") {
            if (!doc.isPhotos || doc.pageCount > 1) {
                OutlinedTextField(
                    value = o.pageRange,
                    onValueChange = { v -> vm.update { it.copy(pageRange = v) } },
                    label = { Text("Pages (e.g. 1-3, 5, 8-)") },
                    placeholder = { Text("All ${doc.pageCount}") },
                    isError = state.rangeError != null,
                    supportingText = { state.rangeError?.let { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Which sides", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SidesMode.entries.forEach { m ->
                    FilterChip(selected = o.sides == m, onClick = { vm.update { it.copy(sides = m) } }, label = { Text(m.label) })
                }
            }
            if (o.sides == SidesMode.GUIDED_DUPLEX) {
                Text("Prints the fronts, then tells you how to put the paper back in to print the backs.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (o.perSheet > 1 && (o.sides == SidesMode.ODD || o.sides == SidesMode.EVEN)) {
                Text("With ${o.perSheet} pages per sheet, odd/even counts sheets.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Layout") {
            Text("Pages per sheet", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Imposition.SUPPORTED.forEach { n ->
                    FilterChip(selected = o.perSheet == n, onClick = { vm.update { it.copy(perSheet = n) } }, label = { Text("$n") })
                }
            }
            Text("Zoom ${o.zoomPercent}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = o.zoomPercent.toFloat(),
                onValueChange = { v -> vm.update { it.copy(zoomPercent = (v / 5).roundToInt() * 5) } },
                valueRange = 25f..300f,
                enabled = o.scale == ScaleMode.FIT,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 100, 150, 200).forEach { z ->
                    FilterChip(selected = o.zoomPercent == z, onClick = { vm.update { it.copy(zoomPercent = z) } }, label = { Text("$z%") })
                }
            }
            SwitchRow("Trim white margins", "Crops empty borders so small PDFs fill the page", o.trimMargins) { v ->
                vm.update { it.copy(trimMargins = v) }
            }
            if (doc.isPhotos) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ScaleMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = o.scale == m, onClick = { vm.update { it.copy(scale = m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ScaleMode.entries.size)) { Text(m.label) }
                    }
                }
                if (printerInfo.info?.supportsBorderless == true) {
                    SwitchRow("Borderless", "Print to the edge of photo paper", o.borderless) { v -> vm.update { it.copy(borderless = v) } }
                }
            }
        }

        SectionCard("Paper & colour") {
            PaperPicker(o, printerInfo.info?.media.orEmpty()) { p -> vm.update { it.copy(paper = p.keyword) } }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !o.mono, onClick = { vm.update { it.copy(mono = false) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Colour") }
                SegmentedButton(selected = o.mono, onClick = { vm.update { it.copy(mono = true) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Black & white") }
            }
            Text("Quality", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3 to "Draft", 4 to "Normal", 5 to "Best").forEachIndexed { i, (q, label) ->
                    SegmentedButton(selected = o.quality == q, onClick = { vm.update { it.copy(quality = q) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                }
            }
            if (o.sides != SidesMode.GUIDED_DUPLEX) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { vm.update { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text("${o.copies}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { vm.update { it.copy(copies = (it.copies + 1).coerceAtMost(99)) } }) { Text("+") }
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val printing = busy is com.justprintit.app.print.PrintState.Working
        Button(
            onClick = { vm.print() },
            enabled = state.rangeError == null && state.sheetCount > 0 && !printing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Outlined.Print, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (printing) "Printer busy…"
                else "Print · ${state.sheetCount} sheet${if (state.sheetCount == 1) "" else "s"}" +
                    if (o.sides == SidesMode.GUIDED_DUPLEX) " (step 1 of 2)" else "",
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PaperPicker(o: PrintOptions, supported: List<String>, onPick: (Paper) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Show only sizes the printer says it takes (when it has told us), plus the current one.
    val papers = Paper.ALL.filter { supported.isEmpty() || it.keyword in supported || it.keyword == o.paper }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Paper: ${Paper.of(o.paper).label}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            papers.forEach { p ->
                DropdownMenuItem(text = { Text(p.label) }, onClick = { onPick(p); open = false })
            }
        }
    }
}
