package com.jasongzy.frpmobile.ui.editor

import com.jasongzy.frpmobile.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jasongzy.frpmobile.ui.components.FrpIconButton
import com.jasongzy.frpmobile.core.frp.FrpType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TomlSuggestions(val text: String, val cursor: Int, val items: List<TomlCompletion>)

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun TomlEditor(state: TextFieldState, type: FrpType, enabled: Boolean, busy: Boolean, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val fields by produceState(emptyList<FrpField>(), type) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("frp-fields.json").bufferedReader().use { readFrpFields(it.readText(), type) }
        }
    }
    var focused by remember { mutableStateOf(false) }
    val text = state.text.toString()
    val selection = state.selection
    val suggestions by produceState<TomlSuggestions?>(null, text, selection, fields, focused, busy) {
        value = if (focused && !busy && selection.collapsed) withContext(Dispatchers.Default) {
            TomlSuggestions(text, selection.start, completeToml(text, selection.start, fields))
        } else null
    }
    fun edit(action: () -> Unit) {
        action()
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TomlTextField(
            state = state,
            enabled = enabled,
            busy = busy,
            modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged { focused = it.hasFocus },
        )
        if (focused) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                val current = suggestions?.takeIf { it.text == text && it.cursor == selection.start && selection.collapsed }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(current?.items.orEmpty(), key = { it.label }) { completion ->
                        SuggestionChip(
                            onClick = {
                                if (state.text.toString() == text && state.selection == selection) {
                                    edit { state.complete(completion) }
                                }
                            },
                            enabled = enabled && !busy,
                            label = { Text(completion.label, fontFamily = FontFamily.Monospace, maxLines = 1) },
                        )
                    }
                }
            }
        }
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
                FrpIconButton(
                    label = stringResource(R.string.editor_undo),
                    modifier = Modifier.size(48.dp),
                    enabled = enabled && !busy && state.undoState.canUndo,
                    onClick = { edit { state.undoState.undo() } },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                }
                FrpIconButton(
                    label = stringResource(R.string.editor_redo),
                    modifier = Modifier.size(48.dp),
                    enabled = enabled && !busy && state.undoState.canRedo,
                    onClick = { edit { state.undoState.redo() } },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = null)
                }
                TomlSymbol.entries.forEach { symbol ->
                    FrpIconButton(
                        label = stringResource(symbol.descriptionRes),
                        modifier = Modifier.size(48.dp),
                        enabled = enabled && !busy,
                        onClick = { edit { state.insertSymbol(symbol) } },
                    ) {
                        Text(
                            text = symbol.open + symbol.close,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp),
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
    }
}

internal enum class TomlSymbol(val open: String, val close: String, val descriptionRes: Int) {
    Table("[", "]", R.string.editor_insert_table),
    ArrayTable("[[", "]]", R.string.editor_insert_array_table),
    Quotes("\"", "\"", R.string.editor_insert_quotes),
    Equals("=", "", R.string.editor_insert_equals),
}

internal fun TextFieldState.insertSymbol(symbol: TomlSymbol) {
    edit {
        val start = selection.min
        val end = selection.max
        if (symbol.close.isEmpty()) {
            replace(start, end, symbol.open)
            selection = TextRange(start + symbol.open.length)
        } else {
            insert(end, symbol.close)
            insert(start, symbol.open)
            selection = TextRange(start + symbol.open.length, end + symbol.open.length)
        }
    }
}
