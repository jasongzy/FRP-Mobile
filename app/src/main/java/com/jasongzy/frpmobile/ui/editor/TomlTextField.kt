package com.jasongzy.frpmobile.ui.editor

import com.jasongzy.frpmobile.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TomlTextField(state: TextFieldState, enabled: Boolean, modifier: Modifier = Modifier) {
    val text = state.text.toString()
    val lineStarts = remember(text) { tomlLineStarts(text) }
    val syntax by produceState<TomlSyntax?>(null, text) {
        value = withContext(Dispatchers.Default) { analyzeToml(text) }
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val styles = remember(colors, dark) {
        mapOf(
            TomlTokenKind.Key to SpanStyle(color = colors.primary),
            TomlTokenKind.Table to SpanStyle(color = colors.tertiary, fontWeight = FontWeight.SemiBold),
            TomlTokenKind.String to SpanStyle(color = if (dark) Color(0xFFA5D6A7) else Color(0xFF216E39)),
            TomlTokenKind.Number to SpanStyle(color = if (dark) Color(0xFFFFB77C) else Color(0xFF965000)),
            TomlTokenKind.Boolean to SpanStyle(color = colors.tertiary),
            TomlTokenKind.Comment to SpanStyle(color = colors.onSurfaceVariant),
        )
    }
    val transformation = remember(syntax, styles) {
        OutputTransformation {
            syntax?.takeIf { it.text.contentEquals(asCharSequence()) }?.tokens?.forEach {
                addStyle(styles.getValue(it.kind), it.start, it.end)
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace, color = colors.onSurface, textDirection = TextDirection.Ltr,
    )
    val measurer = rememberTextMeasurer()
    val digits = lineStarts.size.toString().length.coerceAtLeast(2)
    val numberWidth = measurer.measure("9".repeat(digits), textStyle).size.width
    val gutterWidth = with(LocalDensity.current) { numberWidth.toDp() } + 12.dp
    val label = stringResource(R.string.editor_title)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            BasicTextField(
                state = state,
                enabled = enabled,
                textStyle = textStyle,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                interactionSource = interactionSource,
                cursorBrush = SolidColor(colors.primary),
                outputTransformation = transformation,
                scrollState = scroll,
                onTextLayout = { layout = it() },
                modifier = Modifier.weight(1f).semantics { contentDescription = label },
                decorator = { innerTextField ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxSize()) {
                            Canvas(Modifier.width(gutterWidth).fillMaxHeight().clipToBounds().clearAndSetSemantics {}) {
                                val result = layout ?: return@Canvas
                                if (text != result.layoutInput.text.text) return@Canvas
                                val top = scroll.value.toFloat()
                                val firstOffset = result.getLineStart(result.getLineForVerticalPosition(top))
                                val lastOffset = result.getLineEnd(result.getLineForVerticalPosition(top + size.height))
                                val found = lineStarts.binarySearch(firstOffset)
                                var line = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
                                while (line < lineStarts.size && lineStarts[line] <= lastOffset) {
                                    val visualLine = result.getLineForOffset(lineStarts[line])
                                    val number = measurer.measure((line + 1).toString(), textStyle)
                                    drawText(number, color = colors.onSurfaceVariant, topLeft = Offset(
                                        numberWidth - number.size.width.toFloat(),
                                        result.getLineBaseline(visualLine) - top - number.firstBaseline,
                                    ))
                                    line++
                                }
                                drawLine(colors.outlineVariant, Offset(size.width - 1.dp.toPx(), 0f),
                                    Offset(size.width - 1.dp.toPx(), size.height))
                            }
                            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp)) { innerTextField() }
                        }
                    }
                },
            )
        }
    }
}
