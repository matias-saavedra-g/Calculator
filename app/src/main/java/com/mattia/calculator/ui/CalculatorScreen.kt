package com.mattia.calculator.ui

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import com.mattia.calculator.CalculatorViewModel
import com.mattia.calculator.R
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalculatorScreen(vm: CalculatorViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focus = remember { FocusRequester() }
    val engineStatus by vm.engineStatus.collectAsState()

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportHistory)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importHistory)
    }

    val copy: (String) -> Unit = { text ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Calculator", text)))
            // Android 13+ shows its own clipboard confirmation; older versions get a snackbar.
            if (android.os.Build.VERSION.SDK_INT < 33) snackbar.showSnackbar("Copied")
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(showSearch, showHelp) {
        if (!showSearch && !showHelp) runCatching { focus.requestFocus() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calculator") },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = "Search history")
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Syntax help") }, onClick = { menu = false; showHelp = true })
                        DropdownMenuItem(text = { Text("Export history…") }, onClick = {
                            menu = false
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                            exportLauncher.launch("calculator-history-$stamp.json")
                        })
                        DropdownMenuItem(text = { Text("Import history…") }, onClick = {
                            menu = false
                            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        })
                        DropdownMenuItem(
                            text = { Text("Clear history") },
                            enabled = vm.history.isNotEmpty(),
                            onClick = { menu = false; confirmClear = true },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            HistoryList(
                history = vm.history,
                pending = vm.pending,
                onUse = vm::useEntry,
                onCopy = copy,
                onDelete = vm::deleteEntry,
                onExample = vm::setText,
                modifier = Modifier.weight(1f),
            )
            InputPanel(
                state = vm.input,
                ghost = { vm.ghost?.display.orEmpty() },
                preview = vm.preview,
                engineStatus = engineStatus,
                focusRequester = focus,
                onSubmit = vm::submit,
                onTab = { vm.acceptGhost() },
                onSearch = { showSearch = true },
                onPrev = vm::historyPrev,
                onNext = vm::historyNext,
                onCopy = copy,
            )
            AccessoryBar(
                suggestions = vm.suggestions,
                canAccept = vm.ghost != null || (vm.input.text.isNotEmpty() && vm.suggestions.isNotEmpty()),
                onTab = { vm.acceptGhost() },
                onPick = vm::applyCompletion,
                onPrev = vm::historyPrev,
                onNext = vm::historyNext,
                onSearch = { showSearch = true },
            )
        }
    }

    if (showSearch) {
        HistorySearchSheet(
            history = vm.history,
            onPick = { vm.useEntry(it); showSearch = false },
            onDismiss = { showSearch = false },
        )
    }
    if (showHelp) {
        HelpSheet(
            engineStatus = engineStatus,
            onExample = { vm.setText(it); showHelp = false },
            onDismiss = { showHelp = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This deletes all ${vm.history.size} entries. Export first if you want a copy.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
