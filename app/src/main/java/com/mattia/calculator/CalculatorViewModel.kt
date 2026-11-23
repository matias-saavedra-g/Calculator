package com.mattia.calculator

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mattia.calculator.engine.EngineClient
import com.mattia.calculator.engine.EngineStatus
import com.mattia.calculator.history.HistoryStore
import com.mattia.calculator.input.Completion
import com.mattia.calculator.input.Completions
import com.mattia.calculator.input.Ghost
import com.mattia.calculator.model.EngineResult
import com.mattia.calculator.model.HistoryEntry
import com.mattia.calculator.model.ResultRow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Live result shown above the input while typing. */
data class Preview(val rows: List<ResultRow> = emptyList(), val ok: Boolean = true, val busy: Boolean = false)

class CalculatorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HistoryStore(app)
    private val engine = EngineClient(app, viewModelScope)
    val engineStatus: StateFlow<EngineStatus> = engine.status

    // ------------------------------------------------------------------ UI state
    /** The expression being typed (Compose state-based text field state). */
    val input = TextFieldState()

    var history by mutableStateOf<List<HistoryEntry>>(emptyList())   // oldest -> newest
        private set
    var preview by mutableStateOf(Preview())
        private set
    /** Input currently being computed after Enter (shown as a pending history line). */
    var pending by mutableStateOf<String?>(null)
        private set

    /** Distinct inputs, newest first: source of fish-style suggestions and ↑/↓. */
    private val historyInputs by derivedStateOf { history.asReversed().map { it.input }.distinct() }

    val suggestions: List<Completion> by derivedStateOf {
        Completions.suggest(input.text.toString(), input.selection.end)
    }

    val ghost: Ghost? by derivedStateOf {
        val sel = input.selection
        if (!sel.collapsed) null
        else Completions.ghost(input.text.toString(), sel.end, historyInputs, suggestions)
    }

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    // fish-style ↑/↓ navigation state
    private var navPrefix: String? = null
    private var navIndex = -1
    private var navText: String? = null      // text we put in the field while navigating

    init {
        engine.start()
        viewModelScope.launch { history = store.load() }
        startPreview()
    }

    @OptIn(FlowPreview::class)
    private fun startPreview() = viewModelScope.launch {
        snapshotFlow { input.text.toString() }
            .distinctUntilChanged()
            .also { flow ->
                // Typing anything (other than our own ↑/↓ edits) ends history navigation.
                launch { flow.collect { if (it != navText) { navPrefix = null; navIndex = -1; navText = null } } }
            }
            .debounce(PREVIEW_DEBOUNCE_MS)
            .collectLatest { q ->
                if (q.isBlank()) {
                    preview = Preview()
                    return@collectLatest
                }
                preview = preview.copy(busy = true)
                preview = when (val r = engine.evaluate(q, lastAnswer(), quick = true, timeoutMs = PREVIEW_TIMEOUT_MS)) {
                    is EngineResult.Done -> Preview(r.reply.rows, r.reply.ok)
                    EngineResult.Timeout -> Preview(listOf(ResultRow("…", "Taking long; press Enter to compute it fully")), ok = false)
                    is EngineResult.Failed -> Preview(emptyList(), ok = false)
                }
            }
    }

    // ------------------------------------------------------------------ editing
    private fun replaceInput(text: String, caret: Int = text.length) {
        input.edit {
            replace(0, length, text)
            selection = TextRange(caret.coerceIn(0, text.length))
        }
    }

    fun acceptGhost(): Boolean {
        val g = ghost
        if (g != null) {
            val e = Completions.applyGhost(input.text.toString(), g)
            replaceInput(e.text, e.caret)
            return true
        }
        val first = suggestions.firstOrNull()
        if (first != null && input.text.isNotEmpty()) {
            applyCompletion(first)
            return true
        }
        return false
    }

    fun applyCompletion(c: Completion) {
        val e = Completions.apply(input.text.toString(), input.selection.end, c)
        replaceInput(e.text, e.caret)
    }

    fun setText(text: String) = input.setTextAndPlaceCursorAtEnd(text)

    /** ↑ : older history line containing what was typed when navigation started (fish behaviour). */
    fun historyPrev() {
        val prefix = navPrefix ?: input.text.toString().also { navPrefix = it }
        val matches = historyInputs.filter { it.contains(prefix, ignoreCase = true) && it != prefix }
        if (navIndex + 1 < matches.size) {
            navIndex++
            navText = matches[navIndex]
            replaceInput(matches[navIndex])
        }
    }

    /** ↓ : newer line, and finally back to what was typed. */
    fun historyNext() {
        val prefix = navPrefix ?: return
        val matches = historyInputs.filter { it.contains(prefix, ignoreCase = true) && it != prefix }
        if (navIndex > 0) {
            navIndex--
            navText = matches[navIndex]
            replaceInput(matches[navIndex])
        } else {
            navIndex = -1
            navPrefix = null
            navText = prefix
            replaceInput(prefix)
        }
    }

    // ------------------------------------------------------------------ evaluate
    fun submit() {
        val q = input.text.toString().replace('\n', ' ').trim()
        if (q.isEmpty() || pending != null) return
        pending = q
        replaceInput("")
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val entry = when (val r = engine.evaluate(q, lastAnswer(), quick = false, timeoutMs = SUBMIT_TIMEOUT_MS)) {
                is EngineResult.Done -> HistoryEntry(newId(), q, r.reply.rows.ifEmpty { listOf(ResultRow("—", "No result")) }, r.reply.ok, started, r.reply.ms)
                EngineResult.Timeout -> HistoryEntry(newId(), q, listOf(ResultRow("Timed out", "Gave up after ${SUBMIT_TIMEOUT_MS / 1000} s; the engine was restarted", "")), false, started, SUBMIT_TIMEOUT_MS)
                is EngineResult.Failed -> HistoryEntry(newId(), q, listOf(ResultRow("Engine error", r.message, "")), false, started)
            }
            pending = null
            // fish-like de-duplication: re-running a line moves it to the end
            persist((history.filter { it.input != q } + entry).takeLast(HistoryStore.MAX_ENTRIES))
        }
    }

    /** `ans` = copy value of the newest successful result. */
    private fun lastAnswer(): String =
        history.lastOrNull { it.ok && it.rows.isNotEmpty() && it.rows[0].copy.isNotBlank() }?.rows?.get(0)?.copy.orEmpty()

    // ------------------------------------------------------------------ history
    fun useEntry(e: HistoryEntry) = setText(e.input)

    fun deleteEntry(e: HistoryEntry) = viewModelScope.launch { persist(history.filter { it.id != e.id }) }

    fun clearHistory() = viewModelScope.launch {
        persist(emptyList())
        _messages.send("History cleared")
    }

    fun exportHistory(uri: Uri) = viewModelScope.launch {
        runCatching { store.export(uri, history) }
            .onSuccess { _messages.send("Exported ${history.size} entries") }
            .onFailure { _messages.send("Export failed: ${it.message}") }
    }

    fun importHistory(uri: Uri) = viewModelScope.launch {
        runCatching { store.read(uri) }
            .onSuccess { incoming ->
                val before = history.size
                persist(HistoryStore.merge(history, incoming))
                _messages.send("Imported ${history.size - before} new entries")
            }
            .onFailure { _messages.send("Import failed: not a Calculator history file") }
    }

    private suspend fun persist(list: List<HistoryEntry>) {
        history = list
        runCatching { store.save(list) }.onFailure { _messages.send("Could not save history: ${it.message}") }
    }

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }

    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val PREVIEW_DEBOUNCE_MS = 250L
        const val PREVIEW_TIMEOUT_MS = 8_000L
        const val SUBMIT_TIMEOUT_MS = 45_000L
    }
}
