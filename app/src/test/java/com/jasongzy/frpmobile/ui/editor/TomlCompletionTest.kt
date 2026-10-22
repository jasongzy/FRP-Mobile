package com.jasongzy.frpmobile.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.jasongzy.frpmobile.core.frp.FrpType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class TomlCompletionTest {
    private val metadata = File("src/main/assets/frp-fields.json").readText()

    private fun suggestions(marked: String, type: FrpType = FrpType.Client): List<TomlCompletion> =
        completeToml(marked.replace("|", ""), marked.indexOf('|'), readFrpFields(metadata, type))

    @Test fun fieldsFollowTheClientServerAndTableContext() {
        assertEquals(listOf("serverAddr", "serverPort"), suggestions("ser|").map { it.label })
        assertTrue(suggestions("ser|", FrpType.Server).isEmpty())
        assertEquals(listOf("bindPort"), suggestions("bindP|", FrpType.Server).map { it.label })
        assertEquals(listOf("transport.tls."), suggestions("transport.tl|").map { it.label })
        assertEquals(listOf("trustedCaFile"), suggestions("[transport.tls]\ntru|").map { it.label })
        assertEquals(listOf("proxies"), suggestions("[[pro|]]").map { it.label })
        assertEquals(listOf("transport.tls"), suggestions("[transport.tl|]").map { it.label })
        assertEquals(listOf("serverPort"), suggestions("serverP|\r\n").map { it.label })
        assertEquals(listOf("transport.tls"), suggestions("[transport.tl|]\r\n").map { it.label })
    }

    @Test fun suggestionsAvoidDuplicateKeysButAllowNewArrayEntries() {
        assertTrue(suggestions("serverPort = 7000\nserverP|").isEmpty())
        assertTrue(suggestions("[transport]\ntcpM|\ntcpMux = true").none { it.label == "tcpMux" })
        assertTrue(suggestions("[[proxies]]\nname = 'first'\n[[proxies]]\nna|").any { it.label == "name" })
        assertEquals(listOf("proxies"), suggestions("[[proxies]]\nname = 'first'\n[[pro|]]").map { it.label })
        assertEquals(listOf("proxies.transport"), suggestions(
            "[[proxies]]\n[proxies.transport]\nuseEncryption = true\n[[proxies]]\n[proxies.tr|]",
        ).map { it.label })
    }

    @Test fun valuesUseUpstreamEnumsAndBooleans() {
        assertEquals(listOf("true"), suggestions("loginFailExit = t|").map { it.replacement })
        assertEquals(listOf("\"oidc\""), suggestions("auth.method = \"o|\"").map { it.replacement })
        assertEquals(listOf("'tcp'", "'tcpmux'"), suggestions("[[proxies]]\ntype = 'tc|'").map { it.replacement })
        assertTrue(suggestions("[[proxies]]\nplugin.type = |").any { it.label == "http_proxy" })
        assertTrue(suggestions("serverPort = 70|").isEmpty())
        assertTrue(suggestions("loginFailExit = true|").isEmpty())
    }

    @Test fun commentsAndMultilineStringsDoNotBecomeConfigFields() {
        assertTrue(suggestions("# serverP|").isEmpty())
        assertTrue(suggestions("name = \"\"\"hello\nserverP|\n\"\"\"").isEmpty())
        assertTrue(suggestions("name = '''\n[[proxies]]\n'''\nserverP|").any { it.label == "serverPort" })
        assertTrue(suggestions("start = [\nser|\n]").isEmpty())
    }

    @Test fun completionPreservesExistingValuesAndCommentsAndSupportsUndo() {
        val marked = "  serverP| = 7000 # port"
        val original = marked.replace("|", "")
        val state = TextFieldState(original, TextRange(marked.indexOf('|')))
        state.complete(suggestions(marked).single())
        assertEquals("  serverPort = 7000 # port", state.text.toString())
        state.undoState.undo()
        assertEquals(original, state.text.toString())
        state.undoState.redo()
        assertEquals("  serverPort = 7000 # port", state.text.toString())
    }

    @Test fun insertionFinishesBracketsAndPlacesTheCursorInsideValues() {
        val header = TextFieldState("[[pro]] # tunnel", TextRange(5))
        header.complete(suggestions("[[pro|]] # tunnel").single())
        assertEquals("[[proxies]] # tunnel", header.text.toString())
        assertEquals(TextRange(11), header.selection)
        val state = TextFieldState("serverA", TextRange(7))
        state.complete(suggestions("serverA|").single())
        assertEquals("serverAddr = \"\"", state.text.toString())
        assertEquals(TextRange(14), state.selection)
    }
}
