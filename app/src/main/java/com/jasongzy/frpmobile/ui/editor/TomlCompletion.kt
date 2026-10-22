package com.jasongzy.frpmobile.ui.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.jasongzy.frpmobile.core.frp.FrpType
import org.json.JSONObject

internal data class FrpField(val path: String, val kind: String, val values: List<String> = emptyList())

internal fun readFrpFields(json: String, type: FrpType): List<FrpField> {
    val fields = JSONObject(json).getJSONObject(if (type == FrpType.Client) "frpc" else "frps")
    return fields.keys().asSequence().sorted().map { path ->
        val field = fields.getJSONObject(path)
        val values = field.optJSONArray("values")
        FrpField(path, field.getString("kind"), List(values?.length() ?: 0) { values!!.getString(it) })
    }.toList()
}

internal data class TomlCompletion(
    val label: String,
    val start: Int,
    val end: Int,
    val replacement: String,
    val cursor: Int = replacement.length,
)

internal fun TextFieldState.complete(completion: TomlCompletion) {
    edit {
        replace(completion.start, completion.end, completion.replacement)
        selection = TextRange(completion.start + completion.cursor)
    }
}

private val tableHeader = Regex("(?m)^[ \\t]*(\\[\\[?)([\\w.-]+)\\]\\]?[ \\t]*$")
private val headerInput = Regex("^(\\s*)(\\[\\[?)([\\w.-]*)(\\]*)([ \\t]*)$")
private val keyInput = Regex("^([ \\t]*)([\\w.-]*)([ \\t]*)(=.*)?$")
private val assignment = Regex("(?m)^[ \\t]*([\\w.-]+)[ \\t]*=")

internal fun completeToml(text: String, cursor: Int, fields: List<FrpField>): List<TomlCompletion> {
    if (fields.isEmpty()) return emptyList()
    val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
    val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        .let { if (text.getOrNull(it - 1) == '\r') it - 1 else it }
    val syntax = analyzeToml(text)
    val enclosing = syntax.tokens.firstOrNull { cursor > it.start && cursor <= it.end }
    if (enclosing?.kind == TomlTokenKind.Comment ||
        enclosing?.kind == TomlTokenKind.String &&
        (enclosing.start < lineStart || text.startsWith("\"\"\"", enclosing.start) || text.startsWith("'''", enclosing.start))) {
        return emptyList()
    }
    val masked = text.toCharArray()
    syntax.tokens.filter { it.kind == TomlTokenKind.String || it.kind == TomlTokenKind.Comment }.forEach { token ->
        for (i in token.start until token.end) if (masked[i] != '\n') masked[i] = ' '
    }
    val code = String(masked)
    var depth = 0
    for (char in code.take(lineStart)) {
        if (char == '[' || char == '{') depth++
        if (char == ']' || char == '}') depth--
    }
    if (depth > 0) return emptyList()
    val comment = syntax.tokens.firstOrNull { it.kind == TomlTokenKind.Comment && it.start in lineStart until lineEnd }
    val contentEnd = comment?.start ?: lineEnd
    val line = text.substring(lineStart, contentEnd)
    val headers = tableHeader.findAll(code).toList()
    val header = headers.lastOrNull { it.range.first < lineStart }
    val table = header?.groupValues?.get(2).orEmpty()

    headerInput.matchEntire(line)?.let { match ->
        val start = lineStart + match.groupValues[1].length + match.groupValues[2].length
        val end = start + match.groupValues[3].length
        if (cursor !in start..end) return emptyList()
        val prefix = text.substring(start, cursor)
        val parent = prefix.substringBeforeLast('.', "")
        val array = match.groupValues[2] == "[["
        return fields.filter { field ->
            field.kind == (if (array) "array-table" else "table") &&
                field.path.substringBeforeLast('.', "") == parent && field.path.startsWith(prefix, ignoreCase = true)
        }.map { field ->
            val closing = if (array) "]]" else "]"
            TomlCompletion(field.path, start, end + match.groupValues[4].length, field.path + closing)
        }
    }

    val match = keyInput.matchEntire(line) ?: return emptyList()
    val start = lineStart + match.groupValues[1].length
    val end = start + match.groupValues[2].length
    val fullKey = listOf(table, match.groupValues[2]).filter(String::isNotEmpty).joinToString(".")
    if (cursor > end && match.groupValues[4].isNotEmpty()) {
        val equals = lineStart + line.indexOf('=')
        if (cursor <= equals) return emptyList()
        val valueStart = (equals + 1 until contentEnd).firstOrNull { !text[it].isWhitespace() } ?: contentEnd
        val valueEnd = contentEnd - text.substring(valueStart, contentEnd).takeLastWhile(Char::isWhitespace).length
        if (cursor !in valueStart..valueEnd) return emptyList()
        val value = text.substring(valueStart, valueEnd)
        if (!Regex("[\"']?[\\w-]*[\"']?").matches(value)) return emptyList()
        val field = fields.firstOrNull { it.path == fullKey } ?: return emptyList()
        val options = if (field.kind == "boolean") listOf("true", "false") else field.values
        val prefix = text.substring(valueStart, cursor).trim('"', '\'')
        val quote = if (value.startsWith("'")) "'" else "\""
        return options.filter { it.startsWith(prefix, ignoreCase = true) }.map {
            TomlCompletion(it, valueStart, valueEnd, if (field.kind == "boolean") it else "$quote$it$quote")
        }.filter { it.replacement != value }
    }
    if (cursor !in start..end) return emptyList()
    // shortcut: completion covers line-based keys and tables; add inline-table contexts when needed.
    val prefix = text.substring(start, cursor)
    val parent = prefix.substringBeforeLast('.', "")
    val tablePrefix = if (table.isEmpty()) "" else "$table."
    val sectionStart = header?.range?.last?.plus(1) ?: 0
    val sectionEnd = headers.firstOrNull { it.range.first > lineStart }?.range?.first ?: text.length
    val existing = assignment.findAll(code.substring(sectionStart, sectionEnd)).filter {
        sectionStart + it.range.first !in lineStart..lineEnd
    }.map { it.groupValues[1] }.toSet()
    return fields.mapNotNull { field ->
        if (!field.path.startsWith(tablePrefix) || field.kind == "array-table") return@mapNotNull null
        val key = field.path.removePrefix(tablePrefix)
        if (key.substringBeforeLast('.', "") != parent || !key.startsWith(prefix, ignoreCase = true) || key in existing) {
            return@mapNotNull null
        }
        val nested = field.kind == "table" && fields.any { it.path.startsWith("${field.path}.") }
        val replacement = when {
            match.groupValues[4].isNotEmpty() -> key
            nested -> "$key."
            field.kind == "string" -> "$key = \"\""
            field.kind == "array" -> "$key = []"
            field.kind == "table" -> "$key = {}"
            else -> "$key = "
        }
        val inside = match.groupValues[4].isEmpty() && !nested && field.kind in setOf("string", "array", "table")
        TomlCompletion(if (nested) "$key." else key, start, end, replacement, replacement.length - if (inside) 1 else 0)
    }
}
