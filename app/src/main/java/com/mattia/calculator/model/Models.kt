package com.mattia.calculator.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One answer line produced by the engine: title, subtitle (what was computed), copy value. */
@Serializable
data class ResultRow(
    @SerialName("t") val title: String,
    @SerialName("s") val subtitle: String = "",
    @SerialName("c") val copy: String = title,
)

/** What `calc_engine.evaluate_json` returns. */
@Serializable
data class EngineReply(
    val ok: Boolean,
    val rows: List<ResultRow> = emptyList(),
    val ms: Long = 0,
)

/** One line of history, persisted in files/history.json and in exported JSON files. */
@Serializable
data class HistoryEntry(
    val id: String,
    val input: String,
    val rows: List<ResultRow>,
    val ok: Boolean = true,
    /** Unix epoch milliseconds. */
    val timestamp: Long,
    /** Engine compute time in ms. */
    val ms: Long = 0,
) {
    val answer: String get() = rows.firstOrNull()?.title.orEmpty()
}

/** Top-level document of history.json and of exports (format is documented in docs/HISTORY_FORMAT.md). */
@Serializable
data class HistoryFile(
    val app: String = "Calculator",
    val format: Int = 1,
    val exportedAt: String? = null,
    val entries: List<HistoryEntry> = emptyList(),
)

sealed interface EngineResult {
    data class Done(val reply: EngineReply) : EngineResult
    data object Timeout : EngineResult
    data class Failed(val message: String) : EngineResult
}
