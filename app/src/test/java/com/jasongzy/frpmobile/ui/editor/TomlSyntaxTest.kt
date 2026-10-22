package com.jasongzy.frpmobile.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TomlSyntaxTest {
    @Test fun distinguishesKeysHeadersValuesAndComments() {
        val text = "[[proxies]]\n123.name = \"a # b \\\"c\\\"\" # comment\noptions = { enabled = true, ports = [80, -1.5e2] }"
        val actual = analyzeToml(text).tokens.map { text.substring(it.start, it.end) to it.kind }
        assertEquals(listOf(
            "proxies" to TomlTokenKind.Table,
            "123" to TomlTokenKind.Key, "name" to TomlTokenKind.Key,
            "\"a # b \\\"c\\\"\"" to TomlTokenKind.String, "# comment" to TomlTokenKind.Comment,
            "options" to TomlTokenKind.Key, "enabled" to TomlTokenKind.Key, "true" to TomlTokenKind.Boolean,
            "ports" to TomlTokenKind.Key, "80" to TomlTokenKind.Number, "-1.5e2" to TomlTokenKind.Number,
        ), actual)
    }

    @Test fun multilineStringsAndNestedArraysPreserveContextAndLineNumbers() {
        val quotes = "\"".repeat(3)
        val literal = "'".repeat(3)
        val text = "values = [\r\n[$quotes# text\n[not.a.table]\n$quotes],\n{ 'quoted key' = ${literal}a\nb$literal, end = false }\n]\nnext = 0xFF\n"
        val syntax = analyzeToml(text)
        assertEquals(listOf("values", "'quoted key'", "end", "next"), syntax.tokens
            .filter { it.kind == TomlTokenKind.Key }.map { text.substring(it.start, it.end) })
        assertEquals(2, syntax.tokens.count { it.kind == TomlTokenKind.String })
        assertTrue(syntax.tokens.none { it.kind == TomlTokenKind.Table || it.kind == TomlTokenKind.Comment })
        assertEquals(listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }, tomlLineStarts(text))
        assertEquals(text.length, tomlLineStarts(text).last())
    }

    @Test fun incompleteEditsAlwaysProduceOrderedInBoundsSpans() {
        val text = "[server.\"key\"]\nname = \"escaped\\\" # value\"\nitems = [{ on = true }, [1, 2]]\ntext = \"\"\"line\nend\"\"\"\"\"\n"
        for (end in 0..text.length) {
            val prefix = text.take(end)
            val syntax = analyzeToml(prefix)
            assertEquals(prefix, syntax.text)
            assertTrue(syntax.tokens.all { it.start >= 0 && it.start < it.end && it.end <= end })
            assertTrue(syntax.tokens.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
        val recovered = "broken = \"unfinished\nnext = true"
        assertEquals(TomlTokenKind.Boolean, analyzeToml(recovered).tokens.last().kind)
        assertEquals(listOf(0), tomlLineStarts(""))
        assertEquals(listOf(0, 1, 2), tomlLineStarts("\n\n"))
    }
}
