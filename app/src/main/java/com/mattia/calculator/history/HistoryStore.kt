package com.mattia.calculator.history

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.mattia.calculator.model.HistoryEntry
import com.mattia.calculator.model.HistoryFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Persists the history as JSON in the app's private storage:
 *   /data/data/<package>/files/history.json
 * Writes are atomic (AtomicFile: write to a temp file, then rename), so a crash
 * mid-write can never corrupt the history. The same document format is used for
 * export/import (see docs/HISTORY_FORMAT.md).
 */
class HistoryStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val resolver = context.contentResolver

    suspend fun load(): List<HistoryEntry> = withContext(Dispatchers.IO) {
        try {
            if (!file.baseFile.exists()) emptyList()
            else decode(file.readFully().decodeToString()).entries.sortedBy { it.timestamp }
        } catch (e: Exception) {
            // Keep a copy of an unreadable file instead of silently overwriting it later.
            runCatching { file.baseFile.copyTo(File(file.baseFile.parentFile, "$FILE_NAME.corrupt"), overwrite = true) }
            emptyList()
        }
    }

    suspend fun save(entries: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        val bytes = JSON.encodeToString(HistoryFile.serializer(), HistoryFile(entries = entries)).encodeToByteArray()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    suspend fun export(uri: Uri, entries: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        val doc = HistoryFile(exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), entries = entries)
        resolver.openOutputStream(uri, "wt")?.use { it.write(JSON.encodeToString(HistoryFile.serializer(), doc).encodeToByteArray()) }
            ?: error("Could not open the file")
    }

    /** Reads an exported file. Accepts a full HistoryFile document or a bare entry array. */
    suspend fun read(uri: Uri): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Could not open the file")
        decode(text).entries
    }

    companion object {
        const val FILE_NAME = "history.json"
        const val MAX_ENTRIES = 10_000

        val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): HistoryFile {
            val trimmed = text.trimStart()
            return if (trimmed.startsWith("[")) {
                HistoryFile(entries = JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HistoryEntry.serializer()), trimmed))
            } else {
                JSON.decodeFromString(HistoryFile.serializer(), trimmed)
            }
        }

        /** Union by id, oldest first, capped at [MAX_ENTRIES]. */
        fun merge(current: List<HistoryEntry>, incoming: List<HistoryEntry>): List<HistoryEntry> =
            (current + incoming).associateBy { it.id }.values.sortedBy { it.timestamp }.takeLast(MAX_ENTRIES)
    }
}
