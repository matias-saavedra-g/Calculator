package com.mattia.calculator.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mattia.calculator.R
import com.mattia.calculator.engine.EngineStatus
import com.mattia.calculator.input.Fuzzy
import com.mattia.calculator.model.HistoryEntry

/** Ctrl+R-style fuzzy history search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySearchSheet(history: List<HistoryEntry>, onPick: (HistoryEntry) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberExpandedSheetState()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val distinct = remember(history) { history.asReversed().distinctBy { it.input } }
    val results = remember(query, distinct) { Fuzzy.rank(query, distinct) { it.input }.take(300) }
    val accent = MaterialTheme.colorScheme.primary

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                singleLine = true,
                label = { Text("Search history") },
                placeholder = { Text("fuzzy, e.g. \"intx2\"") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { onPick(it.first) } }),
            )
            Text(
                if (history.isEmpty()) "No history yet" else "${results.size} of ${distinct.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(results, key = { it.first.id }) { (e, m) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(e) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(highlight(e.input, m.positions, accent), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                            Text(e.answer, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            relativeTime(e.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private fun relativeTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return if (now - ts < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(ts, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

private fun highlight(text: String, positions: List<Int>, color: Color): AnnotatedString = buildAnnotatedString {
    val set = positions.toHashSet()
    text.forEachIndexed { i, ch ->
        if (i in set) withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(ch) } else append(ch)
    }
}

private val HELP = listOf(
    "Solve" to listOf("solve x^2 - 4 = 0", "2x + 3 = 7", "x^2 + y^2 = 25, x - y = 1", "x^2 < 4", "ax^2 + bx + c = 0 for x"),
    "Integrate (int)" to listOf("int x^2", "int x e^x dx", "int x^2 from 0 to 1", "int_0^pi sin x", "int e^(-x^2) from -oo to oo", "int x*y dy"),
    "Sum (sum)" to listOf("sum 1 to 100", "sum of all numbers between 1 and 50", "sum 1 to 99 step 2", "sum k^2 from 1 to 10", "sum 1/n^2 from n=1 to oo", "sum 3, 5, 7"),
    "Derivatives & limits" to listOf("d/dx x^3 e^x", "second derivative of sin x", "derivative of x^2 at x=3", "lim x->0 sin(x)/x", "limit of (1+1/x)^x as x->oo"),
    "Algebra" to listOf("factor x^3 - 8", "expand (x+1)^4", "simplify sin(x)^2 + cos(x)^2", "partial fractions 1/(x^2-1)", "taylor series of cos x"),
    "Numbers" to listOf("15% of 200", "pi to 50 digits", "factor 360", "is 97 prime", "255 in hex", "2^64", "ans * 2"),
    "Matrices" to listOf("det [[1,2],[3,4]]", "inverse of [[1,2],[3,4]]", "eigenvalues of [[2,1],[1,2]]"),
    "Wolfram Language" to listOf("Integrate[Sin[x]^2, {x, 0, Pi}]", "Solve[x^2 == 2, x]", "D[x^3, {x, 2}]", "Sum[1/k^2, {k, 1, Infinity}]", "Limit[Sin[x]/x, x -> 0]", "N[E, 30]"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(engineStatus: EngineStatus, onExample: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberExpandedSheetState()) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text("Syntax", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
                Text(
                    "Tap an example to try it. Tab accepts the gray suggestion, ↑/↓ walk your history, the search button fuzzy-finds it. `ans` is the last result.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            HELP.forEach { (title, examples) ->
                item(key = title) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
                items(examples, key = { "$title/$it" }) { ex ->
                    Text(
                        ex,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onExample(ex) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(
                    "Engine: SymPy 1.14 on CPython 3.14 (Chaquopy), ${engineStatus.name.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberExpandedSheetState() =
    rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
