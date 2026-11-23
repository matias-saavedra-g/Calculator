package com.mattia.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mattia.calculator.R
import com.mattia.calculator.input.Completion

/**
 * The strip that sits right on top of the system keyboard:
 *   [⇥ Tab] [completion chips …] [↑] [↓] [history search]
 * Tab accepts the gray "ghost" suggestion (history line or completion), as in fish.
 * Chips are M3 suggestion chips (dynamically generated suggestions).
 */
@Composable
fun AccessoryBar(
    suggestions: List<Completion>,
    canAccept: Boolean,
    onTab: () -> Unit,
    onPick: (Completion) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSearch: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onTab, enabled = canAccept) {
                Icon(painterResource(R.drawable.ic_tab), contentDescription = "Accept suggestion (Tab)")
            }
            val listState = rememberLazyListState()
            LaunchedEffect(suggestions) { listState.scrollToItem(0) }
            LazyRow(
                modifier = Modifier.weight(1f),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                items(suggestions, key = { it.label + it.kind }) { c ->
                    SuggestionChip(
                        onClick = { onPick(c) },
                        label = {
                            Text(
                                if (c.kind == Completion.Kind.Function) "${c.label}()" else c.label,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                    )
                }
            }
            IconButton(onClick = onPrev) { Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "Older history (↑)") }
            IconButton(onClick = onNext) { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "Newer history (↓)") }
            IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_search), contentDescription = "Fuzzy search history (Ctrl+R)") }
        }
    }
}
