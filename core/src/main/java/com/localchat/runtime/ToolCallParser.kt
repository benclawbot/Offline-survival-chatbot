package com.localchat.runtime

import org.json.JSONArray
import org.json.JSONObject

data class ParsedToolCall(val name: String, val argumentsJson: String)

/**
 * Tolerant parser for Hermes-style `<tool_call>{...}</tool_call>` blocks.
 *
 * Small local models routinely emit slightly-malformed JSON: trailing commas,
 * Python-style single quotes, ```code fences```, prose wrapped around the object,
 * or `arguments` delivered as a stringified JSON blob. Research (Qwen function
 * calling) is explicit that prompt engineering can't eliminate these, so we
 * recover from each failure mode before giving up. Pure JVM logic → fully
 * unit-tested in ToolCallParserTest.
 */
object ToolCallParser {

    private const val OPEN_TAG = "<tool_call>"

    /**
     * First tool_call in [text], or null. We anchor on the opening `<tool_call>` and let
     * [parseBody] extract the first balanced `{…}` after it — so this fires as soon as the
     * JSON is complete, even when a small model omits the closing `</tool_call>` tag
     * (verified on Qwen3-0.6B via the desktop harness). The balanced-brace scan means it
     * won't trigger mid-JSON during streaming.
     */
    fun findClosedCall(text: String): ParsedToolCall? {
        val idx = text.indexOf(OPEN_TAG, ignoreCase = true)
        if (idx < 0) return null
        return parseBody(text.substring(idx + OPEN_TAG.length))
    }

    /** Parse the inside of a tool_call (without the tags). Visible for testing. */
    fun parseBody(rawBody: String): ParsedToolCall? {
        val candidate = extractJsonObject(rawBody) ?: return null
        val obj = parseLenient(candidate) ?: return null
        val name = obj.optString("name").trim()
        if (name.isBlank()) return null
        return ParsedToolCall(name, argumentsToJson(obj.opt("arguments")))
    }

    private fun argumentsToJson(args: Any?): String = when (args) {
        is JSONObject -> args.toString()
        is JSONArray -> args.toString()
        null, JSONObject.NULL -> "{}"
        is String -> {
            val s = args.trim()
            if (s.startsWith("{") || s.startsWith("[")) {
                parseLenient(s)?.toString() ?: JSONObject().put("value", args).toString()
            } else {
                JSONObject().put("value", args).toString()
            }
        }
        else -> JSONObject().put("value", args).toString()
    }

    /** Tries strict JSON, then trailing-comma repair, then single→double quote repair. */
    private fun parseLenient(s: String): JSONObject? {
        runCatching { return JSONObject(s) }
        val noTrailingCommas = s.replace(Regex(",\\s*([}\\]])"), "$1")
        runCatching { return JSONObject(noTrailingCommas) }
        val doubleQuoted = singleToDoubleQuotes(noTrailingCommas)
        runCatching { return JSONObject(doubleQuoted) }
        return null
    }

    /** Only convert when the blob has no double quotes at all — avoids mangling apostrophes in values. */
    private fun singleToDoubleQuotes(s: String): String =
        if (s.contains('"')) s else s.replace('\'', '"')

    /**
     * Extracts the first balanced `{...}` object from [raw], ignoring any prose,
     * code fences, or trailing junk around it. String contents are respected so
     * that braces inside string values don't unbalance the scan.
     */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
