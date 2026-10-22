package com.jasongzy.frpmobile.core.data

import com.jasongzy.frpmobile.core.frp.FrpProfile
import com.jasongzy.frpmobile.core.frp.FrpType
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EditorDraftTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun restoreKeepsUnsavedContentAndOriginalRevisionIndependent() {
        val original = FrpProfile("server", "Server", FrpType.Server, "bindPort = 7000", false, 42,
            mapOf("key.pem" to byteArrayOf(1)))
        val current = original.copy(name = "", toml = "unfinished = [\n" + "x".repeat(1024 * 1024),
            files = mapOf("cert.pem" to byteArrayOf(0, -1, 2)))
        for (isNew in listOf(false, true)) {
            val restored = decodeDraft(encodeDraft(EditorDraft(original, current, isNew, 3, 8)))
            assertEquals(original.copy(files = emptyMap()), restored.original.copy(files = emptyMap()))
            assertEquals(current.copy(files = emptyMap()), restored.current.copy(files = emptyMap()))
            assertEquals(isNew, restored.isNew)
            assertEquals(3, restored.selectionStart)
            assertEquals(8, restored.selectionEnd)
            assertArrayEquals(original.files["key.pem"], restored.original.files["key.pem"])
            assertArrayEquals(current.files["cert.pem"], restored.current.files["cert.pem"])
        }
    }

    @Test fun pruningRemovesInterruptedWritesAndKeepsCurrentAndRecentDrafts() {
        val current = "11111111-1111-1111-1111-111111111111"
        val other = "22222222-2222-2222-2222-222222222222"
        val cipher = object : ConfigCipher {
            override fun encrypt(value: ByteArray, identity: String): String = error("Unexpected encryption")
            override fun decrypt(value: String, identity: String): ByteArray = error("Unexpected decryption")
        }
        val recent = directory.newFile("$other.draft")
        val active = directory.newFile("$current.draft.new").apply { setLastModified(1) }
        val orphan = directory.newFile("$other.draft.new").apply { setLastModified(1) }
        val backup = directory.newFile("$other.draft.bak").apply { setLastModified(1) }
        EditorDraftStore(directory.root, cipher).prune(current)
        assertTrue(recent.exists())
        assertTrue(active.exists())
        assertFalse(orphan.exists())
        assertFalse(backup.exists())
    }
}
