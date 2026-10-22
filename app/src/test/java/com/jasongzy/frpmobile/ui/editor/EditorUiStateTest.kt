package com.jasongzy.frpmobile.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.ExperimentalFoundationApi
import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import com.jasongzy.frpmobile.core.frp.newProfileDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class EditorUiStateTest {
    private val profile = FrpProfile("client", "Client", FrpType.Client, "serverPort = 7000", false, 0)
    private val loaded = EditorUiState(profile = profile, loading = false, name = profile.name, toml = TextFieldState(profile.toml))

    @Test fun newProfilesRequireSavingEvenWhenTheTemplateIsUnchanged() {
        FrpType.entries.forEach { type ->
            val profile = newProfileDraft(type)
            val draft = EditorUiState(profile = profile, isNew = true, loading = false, name = profile.name, toml = TextFieldState(profile.toml))
            assertTrue(draft.hasUnsavedChanges)
            assertTrue(draft.copy(validationMessage = "Save failed").hasUnsavedChanges)
            assertFalse(draft.copy(isNew = false).hasUnsavedChanges)
        }
    }

    @Test fun onlyChangesToSavedContentRequireConfirmation() {
        assertFalse(EditorUiState().hasUnsavedChanges)
        assertFalse(loaded.hasUnsavedChanges)
        assertTrue(loaded.copy(name = "Renamed").hasUnsavedChanges)
        assertTrue(loaded.copy(toml = TextFieldState()).hasUnsavedChanges)
        assertFalse(loaded.copy(name = "Renamed").copy(name = profile.name).hasUnsavedChanges)
        loaded.toml.insertSymbol(TomlSymbol.Table)
        assertTrue(loaded.hasUnsavedChanges)
        loaded.toml.undoState.undo()
        assertFalse(loaded.hasUnsavedChanges)
    }

    @Test fun editsRemainPendingUntilSavedContentIsUpdated() {
        val edited = loaded
        edited.toml.insertSymbol(TomlSymbol.Quotes)
        assertTrue(edited.copy(operation = EditorOperation.Save).hasUnsavedChanges)
        assertTrue(edited.copy(validationMessage = "Save failed").hasUnsavedChanges)
        val saved = edited.copy(profile = profile.copy(toml = edited.toml.text.toString()))
        assertFalse(saved.hasUnsavedChanges)
        saved.toml.undoState.undo()
        assertTrue(saved.hasUnsavedChanges)
        saved.toml.undoState.redo()
        assertFalse(saved.hasUnsavedChanges)
    }

    @Test fun certificateChangesRequireSavingAndCompareContents() {
        val original = loaded.copy(
            profile = profile.copy(files = mapOf("key.pem" to byteArrayOf(1))),
            files = mapOf("key.pem" to byteArrayOf(1)),
        )
        assertFalse(original.hasUnsavedChanges)
        assertTrue(original.copy(files = emptyMap()).hasUnsavedChanges)
        assertTrue(original.copy(files = mapOf("key.pem" to byteArrayOf(2))).hasUnsavedChanges)
        assertTrue(loaded.copy(files = original.files).hasUnsavedChanges)
    }

    @Test fun interruptedSaveRestoresTheCommittedRevisionAndAttachments() {
        for (isNew in listOf(false, true)) {
            val draft = loaded.copy(isNew = isNew, name = "Edited", files = mapOf("key.pem" to byteArrayOf(1)))
            val saved = profile.copy(name = draft.name, updatedAt = 42, files = mapOf("key.pem" to byteArrayOf(1)))
            val restored = draft.reconcileSaved(saved)
            assertEquals(saved, restored.profile)
            assertFalse(restored.isNew)
            assertFalse(restored.hasUnsavedChanges)
            assertSame(draft, draft.reconcileSaved(saved.copy(files = mapOf("key.pem" to byteArrayOf(2)))))
            assertSame(draft, draft.reconcileSaved(saved.copy(toml = "changed = true")))
            assertSame(draft, draft.reconcileSaved(saved.copy(id = "other")))
            assertSame(draft, draft.reconcileSaved(saved.copy(storageError = true)))
            assertSame(draft, draft.reconcileSaved(null))
        }
    }
}
