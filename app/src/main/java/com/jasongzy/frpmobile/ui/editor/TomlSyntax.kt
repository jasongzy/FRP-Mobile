package com.jasongzy.frpmobile.ui.editor

internal enum class TomlTokenKind { Key, Table, String, Number, Boolean, Comment }

internal data class TomlToken(val start: Int, val end: Int, val kind: TomlTokenKind)

internal data class TomlSyntax(val text: String, val tokens: List<TomlToken>)

internal fun tomlLineStarts(text: String): List<Int> = buildList {
    add(0)
    text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
}

internal fun analyzeToml(text: String): TomlSyntax {
    val tokens = mutableListOf<TomlToken>()
    val containers = ArrayDeque<Char>()
    var key = true
    var table = false
    var index = 0
    while (index < text.length) {
        val start = index
        val char = text[index]
        when {
            char == '\n' -> {
                if (containers.isEmpty()) key = true
                table = false
                index++
            }
            char.isWhitespace() -> index++
            char == '#' -> {
                while (index < text.length && text[index] != '\n') index++
                tokens.add(TomlToken(start, index, TomlTokenKind.Comment))
            }
            char == '"' || char == '\'' -> {
                val triple = text.getOrNull(index + 1) == char && text.getOrNull(index + 2) == char
                index += if (triple) 3 else 1
                while (index < text.length) {
                    if (!triple && (text[index] == '\n' || text[index] == '\r')) break
                    if (char == '"' && text[index] == '\\') {
                        index++
                        if (index < text.length && (triple || text[index] != '\n' && text[index] != '\r')) index++
                    } else if (text[index] == char) {
                        var quotes = 1
                        while (text.getOrNull(index + quotes) == char) quotes++
                        if (!triple) { index++; break }
                        index += quotes
                        if (quotes >= 3) break
                    } else index++
                }
                tokens.add(TomlToken(start, index, when {
                    table -> TomlTokenKind.Table
                    key -> TomlTokenKind.Key
                    else -> TomlTokenKind.String
                }))
            }
            char == '[' -> {
                if (key && containers.isEmpty()) table = true
                if (!table) containers.addLast(char)
                index++
            }
            char == ']' -> {
                if (table) {
                    while (text.getOrNull(index) == ']') index++
                    table = false
                    key = false
                } else {
                    if (containers.lastOrNull() == '[') containers.removeLast()
                    index++
                }
            }
            char == '{' -> { containers.addLast(char); key = true; index++ }
            char == '}' -> {
                if (containers.lastOrNull() == '{') containers.removeLast()
                key = false
                index++
            }
            char == '=' -> { key = false; index++ }
            char == ',' -> { key = containers.lastOrNull() == '{'; index++ }
            char == '.' && (key || table) -> index++
            else -> {
                while (index < text.length && !text[index].isWhitespace() &&
                    text[index] !in "[]{}=,#\"'" && !(text[index] == '.' && (key || table))) index++
                val word = text.substring(start, index)
                val kind = when {
                    table -> TomlTokenKind.Table
                    key -> TomlTokenKind.Key
                    word == "true" || word == "false" -> TomlTokenKind.Boolean
                    char.isDigit() || char == '+' || char == '-' || word == "inf" || word == "nan" -> TomlTokenKind.Number
                    else -> null
                }
                if (kind != null) tokens.add(TomlToken(start, index, kind))
            }
        }
    }
    return TomlSyntax(text, tokens)
}
