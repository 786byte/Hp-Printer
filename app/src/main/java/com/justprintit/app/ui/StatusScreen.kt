package com.justprintit.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.print.PrintState
import com.justprintit.core.ipp.InkMarker
import com.justprintit.core.ipp.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusScreen(modifier: Modifier, onGoToSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as JustPrintItApp
    val status by app.printer.status.collectAsState()
    val settings by app.settings.values.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    // Live: refresh every 5 s while this screen is visible (ink + state), the page counter every minute.
    LaunchedEffect(settings.printerHost) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var n = 0
            while (true) {
                app.printer.refresh(withUsage = n % 12 == 0)
                n++
                delay(5_000)
            }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (settings.printerHost.isBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("No printer set up yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Connect your phone to the same Wi-Fi as the printer, then find it in Settings.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onGoToSettings) { Text("Find my printer") }
                }
            }
            return@Column
        }

        val info = status.info
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info?.makeAndModel ?: settings.printerName.ifBlank { "Printer" }, style = MaterialTheme.typography.titleLarge)
                Text(settings.printerHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = { scope.launch { app.printer.refresh() } }) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }

        JobProgressCard()

        // State banner
        val (icon, tint, title) = when {
            status.error != null -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "Offline")
            info == null -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Connecting…")
            info.errors.isNotEmpty() -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, info.stateText)
            info.reasons.any { it.severity == Severity.WARNING } -> Triple(Icons.Outlined.WarningAmber, Color(0xFFE09B00), info.stateText)
            else -> Triple(Icons.Outlined.CheckCircle, Color(0xFF2E7D32), info.stateText)
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                status.error?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                info?.reasons?.filter { it.severity != Severity.INFO }?.forEach { r ->
                    Text("• ${r.text}", Modifier.padding(top = 6.dp),
                        color = if (r.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                info?.stateMessage?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
                if (status.updatedAt > 0) {
                    Text("Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(status.updatedAt))}",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Ink
        if (info != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ink levels", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    if (info.markers.isEmpty()) Text("The printer isn't reporting ink levels.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        info.markers.forEach { InkTank(it) }
                    }
                    val low = info.markers.filter { it.isLow }
                    if (low.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Low: ${low.joinToString { it.name }}. Refill from the bottle before the tank reaches the minimum line.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Ink tank printers estimate levels; the window on the front of each tank is the real reading. " +
                        "After refilling, reset the level on the printer if it asks.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        status.usage?.let { u ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Printer's own page counter", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("${u.totalImpressions} pages in its lifetime", style = MaterialTheme.typography.headlineSmall)
                    if (u.colorImpressions != null && u.monoImpressions != null) {
                        Text("${u.colorImpressions} colour · ${u.monoImpressions} black & white")
                    }
                    u.scans?.let { Text("$it scans") }
                    Text("Includes copies made on the printer and prints from other devices.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        val ctx = LocalContext.current
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${settings.printerHost}/")))
        }) {
            Icon(Icons.Outlined.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text("Open printer's web page")
        }
        info?.firmware?.let {
            Text("Firmware $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InkTank(m: InkMarker) {
    val colors = m.colors.mapNotNull { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        .ifEmpty { listOf(Color.Gray) }
    val level = if (m.isKnown) m.level / 100f else 0f
    val outline = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Canvas(Modifier.width(40.dp).height(110.dp)) {
            val r = CornerRadius(8.dp.toPx())
            drawRoundRect(track, cornerRadius = r)
            val h = size.height * level
            val brush = if (colors.size > 1) Brush.horizontalGradient(colors) else Brush.linearGradient(listOf(colors[0], colors[0]))
            drawRoundRect(brush, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r)
            // low-level line
            val lowY = size.height * (1 - m.lowLevel.coerceIn(1, 100) / 100f)
            drawLine(outline, Offset(0f, lowY), Offset(size.width, lowY), strokeWidth = 1.5.dp.toPx())
            drawRoundRect(outline, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                m.isKnown -> "${m.level}%"
                m.level == -3 -> "OK"
                else -> "?"
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (m.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(m.name.removeSuffix(" ink").removeSuffix(" Ink"), style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}

/** Progress of the job being printed, with Cancel. Shown on Status and Print. */
@Composable
fun JobProgressCard() {
    val app = LocalContext.current.applicationContext as JustPrintItApp
    val s by app.queue.state.collectAsState()
    val w = s as? PrintState.Working ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(w.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(w.step, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (w.totalSheets > 0 && w.sheet > 0) {
                LinearProgressIndicator(progress = { w.sheet / w.totalSheets.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                OutlinedButton(onClick = { app.queue.cancel() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
