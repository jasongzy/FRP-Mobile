package com.jasongzy.frpmobile.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class TomlEditorTest {
    @Test fun pairedSymbolsKeepTheCursorInsideAndCanBeUndoneAndRedone() {
        listOf(TomlSymbol.Table, TomlSymbol.ArrayTable, TomlSymbol.Quotes).forEach { symbol ->
            val state = TextFieldState("a\nb", TextRange(2))
            state.insertSymbol(symbol)
            assertEquals("a\n${symbol.open}${symbol.close}b", state.text.toString())
            assertEquals(TextRange(2 + symbol.open.length), state.selection)
            state.undoState.undo()
            assertEquals("a\nb", state.text.toString())
            assertEquals(TextRange(2), state.selection)
            state.undoState.redo()
            assertEquals("a\n${symbol.open}${symbol.close}b", state.text.toString())
            assertEquals(TextRange(2 + symbol.open.length), state.selection)
        }
    }

    @Test fun bracketsWrapAReversedSelectionAndPreserveItsContents() {
        val state = TextFieldState("a proxies b", TextRange(9, 2))
        state.insertSymbol(TomlSymbol.ArrayTable)
        assertEquals("a [[proxies]] b", state.text.toString())
        assertEquals(TextRange(4, 11), state.selection)
        state.undoState.undo()
        assertEquals("a proxies b", state.text.toString())
        assertEquals(TextRange(9, 2), state.selection)
    }

    @Test fun equalsReplacesSelectionAndANewEditClearsRedo() {
        val state = TextFieldState("key : 7", TextRange(4, 5))
        state.insertSymbol(TomlSymbol.Equals)
        assertEquals("key = 7", state.text.toString())
        assertEquals(TextRange(5), state.selection)
        state.undoState.undo()
        assertTrue(state.undoState.canRedo)
        state.insertSymbol(TomlSymbol.Quotes)
        assertEquals("key \":\" 7", state.text.toString())
        assertFalse(state.undoState.canRedo)
    }
}
