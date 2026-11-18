package com.justprintit.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.justprintit.app.JustPrintItApp
import com.justprintit.app.data.JobRecord
import com.justprintit.app.data.JobStatus
import com.justprintit.core.stats.CounterSummary
import com.justprintit.core.stats.Counters
import com.justprintit.core.stats.Tally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun CountersScreen(modifier: Modifier) {
    val app = LocalContext.current.applicationContext as JustPrintItApp
    val changes by app.jobs.changes.collectAsState()
    val data by produceState<Pair<CounterSummary, List<JobRecord>>?>(null, changes) {
        value = withContext(Dispatchers.IO) {
            Counters.summarize(app.jobs.printRecords(), ZoneId.systemDefault()) to app.jobs.recent(50)
        }
    }
    var confirmRetry by remember { mutableStateOf<JobRecord?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val (summary, history) = data ?: return@Column
        Text("Pages printed", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TallyCard("Today", summary.today, Modifier.weight(1f))
            TallyCard("This month", summary.thisMonth, Modifier.weight(1f))
            TallyCard("All time", summary.allTime, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(InkColors.color); Text(" Colour   ", style = MaterialTheme.typography.labelMedium)
            Dot(InkColors.mono); Text(" Black & white", style = MaterialTheme.typography.labelMedium)
        }

        SectionCard("Last 30 days") {
            StackedBars(summary.daily.map { it.second }, Modifier.fillMaxWidth().height(120.dp))
            Row(Modifier.fillMaxWidth()) {
                val fmt = DateTimeFormatter.ofPattern("d MMM")
                Text(summary.daily.first().first.format(fmt), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text("Today", style = MaterialTheme.typography.labelSmall)
            }
        }

        SectionCard("By month") {
            summary.monthly.reversed().filter { it.second.total > 0 }.ifEmpty { null }?.forEach { (m, t) ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${m.year}", Modifier.weight(1f))
                    Text("${t.total}", fontWeight = FontWeight.SemiBold)
                    Text("  (${t.color} colour · ${t.mono} B&W)", style = MaterialTheme.typography.bodySmall)
                }
            } ?: Text("Nothing printed yet.")
        }

        SectionCard("Recent jobs") {
            if (history.isEmpty()) Text("No jobs yet.")
            history.forEachIndexed { i, j ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(j.created))} · " +
                                "${j.status.label} · ${j.pages} page${if (j.pages == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (j.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (j.status == JobStatus.FAILED || j.status == JobStatus.INTERRUPTED) {
                            j.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                        }
                    }
                    if (j.canRetry) TextButton(onClick = { confirmRetry = j }) { Text("Retry…") }
                }
            }
        }
        TextButton(onClick = { confirmReset = true }) { Text("Reset counters and history") }
        Spacer(Modifier.height(24.dp))
    }

    confirmRetry?.let { j ->
        AlertDialog(
            onDismissRequest = { confirmRetry = null },
            title = { Text("Print “${j.name}” again?") },
            text = { Text("It will be sent to the printer again with the same settings." +
                if (j.pages > 0) " ${j.pages} page${if (j.pages == 1) " was" else "s were"} already printed last time." else "") },
            confirmButton = { TextButton(onClick = { app.queue.retry(j.id); confirmRetry = null }) { Text("Print again") } },
            dismissButton = { TextButton(onClick = { confirmRetry = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("This clears all page counters and the job history. It can't be undone.") },
            confirmButton = { TextButton(onClick = { app.jobs.clearHistory(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TallyCard(title: String, t: Tally, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text("${t.total}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(InkColors.color); Text(" ${t.color}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Dot(InkColors.mono); Text(" ${t.mono}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StackedBars(values: List<Tally>, modifier: Modifier) {
    val max = (values.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val slot = size.width / values.size
        val barW = slot * 0.7f
        values.forEachIndexed { i, t ->
            val x = i * slot + (slot - barW) / 2
            val monoH = size.height * t.mono / max
            val colorH = size.height * t.color / max
            drawRect(InkColors.mono, Offset(x, size.height - monoH), Size(barW, monoH))
            drawRect(InkColors.color, Offset(x, size.height - monoH - colorH), Size(barW, colorH))
        }
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}
