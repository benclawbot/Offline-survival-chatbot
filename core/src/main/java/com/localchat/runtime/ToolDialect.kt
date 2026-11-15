package com.localchat.runtime

import org.json.JSONObject

/**
 * Per-model-family tool-calling format. Since litert-community bundles don't ship
 * tool-aware templates (verified on the desktop harness), we inject the protocol
 * into the system prompt and parse the model's text ourselves — in the format the
 * model was trained on:
 *  - [Hermes]: Qwen/Llama/Phi style — `<tool_call>{json}</tool_call>`.
 *  - [Gemma]:  Gemma 3/4 style — a ```tool_code block with a Python-style call,
 *    results returned in a ```tool_output block.
 *
 * A dialect owns four things: the system-prompt protocol, how to parse a call out
 * of streamed text, how to render a tool result back to the model, and how to strip
 * its markup from the user-visible answer.
 */
sealed interface ToolDialect {

    fun protocol(tools: List<ToolSpec>): String
    fun parseCall(text: String): ParsedToolCall?
    fun renderResult(toolName: String, argumentsJson: String, summary: String, ok: Boolean, payload: String, hint: String): String
    fun stripMarkup(text: String): String

    companion object {
        /**
         * Preferred selector: pick the dialect from the family the ENGINE reported for the
         * actual bundle (via ModelTemplateProbe), not the file name. [TemplateFamily.GEMMA] →
         * [Gemma]; ChatML and unknown → [Hermes] (the broadly-compatible default).
         */
        fun forFamily(family: TemplateFamily): ToolDialect =
            if (family == TemplateFamily.GEMMA) Gemma else Hermes

        /** Fallback when no runtime probe is available yet: guess from id/name. */
        fun forModel(modelId: String, modelName: String): ToolDialect {
            val s = (modelId + " " + modelName).lowercase()
            return if (s.contains("gemma")) Gemma else Hermes
        }
    }

    /** Qwen / Hermes / ChatML: `<tool_call>{"name":...,"arguments":{...}}</tool_call>`. */
    object Hermes : ToolDialect {
        override fun protocol(tools: List<ToolSpec>): String = buildToolsSystem(tools)

        override fun parseCall(text: String): ParsedToolCall? = ToolCallParser.findClosedCall(text)

        override fun renderResult(
            toolName: String, argumentsJson: String, summary: String, ok: Boolean, payload: String, hint: String,
        ): String = buildString {
            append("\n\n[Tool call executed]\n")
            append("name: ").append(toolName).append('\n')
            append("arguments: ").append(argumentsJson).append('\n')
            append("ok: ").append(ok).append('\n')
            append("summary: ").append(summary).append('\n')
            append("<tool_result>").append(payload).append("</tool_result>\n")
            append(hint)
        }

        override fun stripMarkup(text: String): String = stripTagMarkup(
            text,
            closed = listOf(TOOL_CALL_RX, TOOL_RESULT_RX),
            openTags = listOf("<tool_call>", "<tool_result>"),
        )

        private val TOOL_CALL_RX = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>", RegexOption.IGNORE_CASE)
        private val TOOL_RESULT_RX = Regex("<tool_result>\\s*[\\s\\S]*?\\s*</tool_result>", RegexOption.IGNORE_CASE)
    }

    /** Gemma 3/4: ```tool_code\n func(arg="v") \n``` ; results in ```tool_output. */
    object Gemma : ToolDialect {
        override fun protocol(tools: List<ToolSpec>): String = buildString {
            append(
                """
                You are an agent. The following tools are available as Python functions:
                """.trimIndent(),
            )
            append("\n```python\n")
            tools.forEach { t ->
                append("def ").append(t.name).append("(").append(pySignature(t)).append("):\n")
                append("    \"\"\"").append(t.description).append("\"\"\"\n")
            }
            append("```\n")
            append(
                """
                To call a tool, output a tool_code block with ONE Python call, then stop:
                ```tool_code
                ${tools.firstOrNull()?.let { "${it.name}(${pyExample(it)})" } ?: "tool(arg=\"value\")"}
                ```
                You will then receive the result in a ```tool_output block — continue from it.
                For stable general knowledge, answer directly without any tool.
                Never invent tool results. Use as few tool calls as possible.
                """.trimIndent(),
            )
        }

        override fun parseCall(text: String): ParsedToolCall? {
            // Find a tool_code fence (closed ``` or running to end-of-stream), then the python call.
            val open = text.indexOf("```tool_code", ignoreCase = true)
            if (open < 0) return null
            var body = text.substring(open + "```tool_code".length)
            val close = body.indexOf("```")
            if (close >= 0) body = body.substring(0, close)
            return parsePythonCall(body.trim())
        }

        override fun renderResult(
            toolName: String, argumentsJson: String, summary: String, ok: Boolean, payload: String, hint: String,
        ): String = buildString {
            append("\n```tool_output\n")
            append(payload).append('\n')
            append("```\n")
            append(hint)
        }

        override fun stripMarkup(text: String): String = stripTagMarkup(
            text,
            closed = listOf(TOOL_CODE_RX, TOOL_OUTPUT_RX),
            openTags = listOf("```tool_code", "```tool_output"),
            // Don't hide a partial ``` tail — that would eat the user's own markdown code
            // fences. The full ```tool_code open tag is still hidden once it appears.
            hidePartialTail = false,
        )

        private val TOOL_CODE_RX = Regex("```tool_code\\s*[\\s\\S]*?```", RegexOption.IGNORE_CASE)
        private val TOOL_OUTPUT_RX = Regex("```tool_output\\s*[\\s\\S]*?```", RegexOption.IGNORE_CASE)

        private fun pySignature(t: ToolSpec): String =
            // Crude but sufficient: list the arg names from the spec's example JSON.
            runCatching {
                JSONObject(t.arguments.replace(Regex("//.*"), "")).keys().asSequence().joinToString(", ") { "$it" }
            }.getOrDefault("query")

        private fun pyExample(t: ToolSpec): String =
            runCatching {
                val obj = JSONObject(t.example).optJSONObject("arguments") ?: JSONObject()
                obj.keys().asSequence().joinToString(", ") { k ->
                    val v = obj.get(k)
                    if (v is String) "$k=\"$v\"" else "$k=$v"
                }
            }.getOrDefault("query=\"...\"")

        /** Parse `name(key="v", n=3)` → ParsedToolCall(name, {"key":"v","n":3}). */
        private fun parsePythonCall(body: String): ParsedToolCall? {
            val lp = body.indexOf('(')
            if (lp <= 0) return null
            val name = body.substring(0, lp).trim().substringAfterLast(' ').substringAfterLast('=').trim()
            if (name.isBlank() || !name.first().isLetter()) return null
            // Balanced parens for the argument list.
            var depth = 0; var end = -1; var inStr = false; var quote = ' '; var esc = false
            for (i in lp until body.length) {
                val c = body[i]
                if (inStr) { when { esc -> esc = false; c == '\\' -> esc = true; c == quote -> inStr = false } }
                else when (c) {
                    '"', '\'' -> { inStr = true; quote = c }
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            // No balanced ')' yet → the call is still streaming; wait (don't parse garbage args
            // and self-stop early, which would also poison the dedup set).
            if (end <= lp) return null
            val argsStr = body.substring(lp + 1, end)
            val args = JSONObject()
            for (pair in splitTopLevel(argsStr)) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = pair.substring(0, eq).trim()
                val raw = pair.substring(eq + 1).trim()
                if (key.isEmpty() || raw.isEmpty()) continue
                args.put(key, coerce(raw))
            }
            return ParsedToolCall(name, args.toString())
        }

        private fun coerce(raw: String): Any = when {
            (raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2) ||
                (raw.startsWith('\'') && raw.endsWith('\'') && raw.length >= 2) -> raw.substring(1, raw.length - 1)
            raw == "true" || raw == "True" -> true
            raw == "false" || raw == "False" -> false
            raw.toIntOrNull() != null -> raw.toInt()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            else -> raw
        }

        /** Split an arg list on top-level commas (ignoring commas inside quotes/brackets). */
        private fun splitTopLevel(s: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            var depth = 0; var inStr = false; var quote = ' '; var esc = false
            for (c in s) {
                when {
                    inStr -> { sb.append(c); when { esc -> esc = false; c == '\\' -> esc = true; c == quote -> inStr = false } }
                    c == '"' || c == '\'' -> { inStr = true; quote = c; sb.append(c) }
                    c == '(' || c == '[' || c == '{' -> { depth++; sb.append(c) }
                    c == ')' || c == ']' || c == '}' -> { depth--; sb.append(c) }
                    c == ',' && depth == 0 -> { out.add(sb.toString()); sb.clear() }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotBlank()) out.add(sb.toString())
            return out
        }
    }
}

/** Shared: remove closed tool blocks, then optionally hide a dangling open-tag prefix at the tail. */
private fun stripTagMarkup(
    text: String,
    closed: List<Regex>,
    openTags: List<String>,
    hidePartialTail: Boolean = true,
): String {
    var out = text
    closed.forEach { out = out.replace(it, "") }
    openTags.forEach { open ->
        val idx = out.indexOf(open, ignoreCase = true)
        if (idx >= 0) out = out.substring(0, idx)
    }
    // Hide a partial opening tag at the very end while it's still being typed (anti-flicker).
    // Skipped for dialects whose tag prefix collides with normal text (e.g. Gemma's ``` fence).
    if (hidePartialTail) {
        val maxPrefix = openTags.maxOf { it.length }
        for (n in 1..maxPrefix) {
            if (out.length < n) break
            val tail = out.substring(out.length - n)
            if (openTags.any { it.startsWith(tail, ignoreCase = true) }) {
                out = out.substring(0, out.length - n)
                break
            }
        }
    }
    return out
}
