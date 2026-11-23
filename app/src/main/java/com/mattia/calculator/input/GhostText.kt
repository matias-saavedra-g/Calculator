package com.mattia.calculator.input

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle

/**
 * Draws the ghost suggestion in a faded colour after the real text, like fish's
 * autosuggestions. An OutputTransformation changes only what is displayed: the
 * ghost is never part of the field's value, and the caret cannot enter it.
 * [ghost] is read inside the transformation, so it re-runs when the ghost changes.
 */
class GhostOutputTransformation(private val ghost: () -> String, private val color: Color) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val g = ghost()
        if (g.isEmpty()) return
        val start = length
        append(g)
        addStyle(SpanStyle(color = color), start, length)
    }
}

/** Newlines (from paste or a hardware Enter that slipped through) become spaces. */
object NoNewlines : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val s = asCharSequence()
        for (i in s.indices.reversed()) if (s[i] == '\n' || s[i] == '\r') replace(i, i + 1, " ")
    }
}
