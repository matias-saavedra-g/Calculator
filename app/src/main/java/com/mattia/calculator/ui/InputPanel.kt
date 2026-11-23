package com.mattia.calculator.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mattia.calculator.Preview
import com.mattia.calculator.R
import com.mattia.calculator.engine.EngineStatus
import com.mattia.calculator.input.GhostOutputTransformation
import com.mattia.calculator.input.NoNewlines

@Composable
fun InputPanel(
    state: TextFieldState,
    ghost: () -> String,
    preview: Preview,
    engineStatus: EngineStatus,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onTab: () -> Unit,
    onSearch: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp)) {
            // ---- live preview
            val first = preview.rows.firstOrNull()
            val blank = state.text.isBlank()
            val status = when {
                blank -> null
                engineStatus == EngineStatus.Starting -> "Starting math engine…"
                engineStatus == EngineStatus.Restarting -> "Restarting math engine…"
                else -> null
            }
            AnimatedVisibility(visible = !blank && (status != null || (first != null && preview.ok) || (first != null && first.title == "…"))) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (preview.busy) 0.55f else 1f)
                        .clickable(enabled = first != null && preview.ok) { first?.let { onCopy(it.copy) } }
                        .padding(bottom = 4.dp),
                ) {
                    if (status != null && first == null) {
                        Text(status, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    } else if (first != null) {
                        Text(
                            "= ${first.title}",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.primary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (first.subtitle.isNotBlank()) {
                            Text(first.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // ---- input line
            Row(verticalAlignment = Alignment.CenterVertically) {
                MathField(
                    state = state,
                    ghost = ghost,
                    focusRequester = focusRequester,
                    onSubmit = onSubmit,
                    onTab = onTab,
                    onSearch = onSearch,
                    onPrev = onPrev,
                    onNext = onNext,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = onSubmit, enabled = !blank) {
                    Icon(painterResource(R.drawable.ic_keyboard_return), contentDescription = "Calculate")
                }
            }
        }
    }
}

/**
 * The input field. It uses the phone's normal keyboard (KeyboardType.Text), not a
 * calculator keypad. Keyboard suggestions and autocorrect are turned off ("sin" must
 * not become "sun"), so the keyboard's own strip doesn't compete with our accessory bar.
 */
@Composable
private fun MathField(
    state: TextFieldState,
    ghost: () -> String,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    onTab: () -> Unit,
    onSearch: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val ghostColor = colors.onSurfaceVariant.copy(alpha = 0.45f)
    val style = MaterialTheme.typography.headlineSmall.copy(color = colors.onSurface, fontFamily = FontFamily.Monospace)
    NoKeyboardSuggestions {
        BasicTextField(
            state = state,
            modifier = modifier
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { e ->
                    // Hardware keyboard (or scrcpy) shortcuts, same as a shell.
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.Tab -> { onTab(); true }
                        e.isCtrlPressed && e.key == Key.R -> { onSearch(); true }
                        e.key == Key.DirectionUp -> { onPrev(); true }
                        e.key == Key.DirectionDown -> { onNext(); true }
                        e.key == Key.Enter || e.key == Key.NumPadEnter -> { onSubmit(); true }
                        e.key == Key.DirectionRight && state.selection.end == state.text.length && ghost().isNotEmpty() -> { onTab(); true }
                        else -> false
                    }
                }
                .padding(vertical = 14.dp),
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Go,
            ),
            onKeyboardAction = { onSubmit() },
            inputTransformation = NoNewlines,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
            outputTransformation = remember(ghost, ghostColor) { GhostOutputTransformation(ghost, ghostColor) },
            decorator = TextFieldDecorator { inner ->
                Box {
                    if (state.text.isEmpty()) Text("Type math…", style = style.copy(color = ghostColor))
                    inner()
                }
            },
        )
    }
}

/** Adds TYPE_TEXT_FLAG_NO_SUGGESTIONS to the EditorInfo the keyboard receives. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoKeyboardSuggestions(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val modified = object : PlatformTextInputMethodRequest by request {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val ic = request.createInputConnection(outAttributes)
                    outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    return ic
                }
            }
            nextHandler.startInputMethod(modified)
        },
        content = content,
    )
}
