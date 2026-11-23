package com.mattia.calculator.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mattia.calculator.model.HistoryEntry

val EXAMPLES = listOf("solve x^2 - 5x + 6 = 0", "int x^2 from 0 to 1", "sum 1 to 100", "d/dx sin(x)^2", "Integrate[Sin[x]^2, x]")

/** The "tape": newest entry at the bottom, right next to the input, like a shell. */
@Composable
fun HistoryList(
    history: List<HistoryEntry>,
    pending: String?,
    onUse: (HistoryEntry) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onExample: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty() && pending == null) {
        EmptyState(onExample, modifier)
        return
    }
    val state = rememberLazyListState()
    val newestFirst = remember(history) { history.asReversed() }
    LaunchedEffect(history.size, pending) { state.animateScrollToItem(0) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = state,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        if (pending != null) item(key = "pending") { PendingItem(pending) }
        items(newestFirst, key = { it.id }) { e -> HistoryItem(e, onUse, onCopy, onDelete) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    e: HistoryEntry,
    onUse: (HistoryEntry) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onUse(e) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                    onClickLabel = "Edit this calculation",
                    onLongClickLabel = "Copy or delete",
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                e.input,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
            e.rows.firstOrNull()?.let { r ->
                Text(r.title, style = MaterialTheme.typography.headlineSmall, color = if (e.ok) colors.onSurface else colors.error, textAlign = TextAlign.End)
                if (r.subtitle.isNotBlank()) {
                    Text(r.subtitle, style = MaterialTheme.typography.labelMedium, color = colors.outline, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            e.rows.drop(1).take(4).forEach { r ->
                Spacer(Modifier.height(6.dp))
                Text(r.title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, textAlign = TextAlign.End)
                if (r.subtitle.isNotBlank()) Text(r.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.outline, textAlign = TextAlign.End)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            e.rows.filter { it.copy.isNotBlank() }.take(4).forEachIndexed { i, r ->
                DropdownMenuItem(
                    text = { Text(if (i == 0) "Copy result" else "Copy “${r.copy.take(28)}${if (r.copy.length > 28) "…" else ""}”") },
                    onClick = { menu = false; onCopy(r.copy) },
                )
            }
            DropdownMenuItem(text = { Text("Copy input") }, onClick = { menu = false; onCopy(e.input) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(e) })
        }
    }
}

@Composable
private fun PendingItem(input: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
        Text(input, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.width(96.dp))
    }
}

@Composable
private fun EmptyState(onExample: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Text("Type math with your keyboard", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text("Try one of these:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EXAMPLES.forEach { ex ->
            TextButton(onClick = { onExample(ex) }) {
                Text(ex, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
