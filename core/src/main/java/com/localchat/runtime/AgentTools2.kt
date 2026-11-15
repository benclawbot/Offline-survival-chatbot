package com.localchat.runtime

/**
 * Tool descriptor exposed to the model. The model emits
 * `<tool_call>{"name":..., "arguments":{...}}</tool_call>` inline; we parse,
 * run the tool, and inject `<tool_result>...</tool_result>` back into the loop.
 *
 * The surface is deliberately small (3 tools). Tool-call reliability on small
 * local models degrades as the tool count grows, so we expose one strong tool
 * per intent: research the web, open a specific page, search local files.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** Pretty-printed JSON schema fragment. */
    val arguments: String,
    val example: String,
)

object AgentTools2 {
    /** Search the web AND read the top sources in one call — the primary fact tool. */
    val WEB_RESEARCH = ToolSpec(
        name = "web_research",
        description = "Searches the web and reads the top sources in one step, returning their text. Use for any current or factual question: news, prices, laws, releases, people, weather, sports, dates.",
        arguments = """{ "query": "search query", "top_k": 3 }""",
        example = """{"name":"web_research","arguments":{"query":"current Tokyo weather","top_k":3}}""",
    )

    /** Read one specific URL — to drill into a link surfaced by web_research. */
    val OPEN_URL = ToolSpec(
        name = "open_url",
        description = "Fetches one specific URL and returns its main text. Use to read a particular link in depth (e.g. a source returned by web_research).",
        arguments = """{ "url": "https://example.com/page" }""",
        example = """{"name":"open_url","arguments":{"url":"https://en.wikipedia.org/wiki/Tokyo"}}""",
    )

    /** Local lexical search over attached files and pages already read this chat. */
    val SEARCH_FILES = ToolSpec(
        name = "search_files",
        description = "Searches this phone's offline survival and emergency reference library (medical, first aid, food, water, shelter, hazards) plus files attached in this chat. Use for every survival, emergency, health, or practical how-to question. Results include source titles, links, licenses, and source passages.",
        arguments = """{ "query": "question or terms", "top_k": 4, "filename": "optional file/page filter" }""",
        example = """{"name":"search_files","arguments":{"query":"section about authentication","top_k":3}}""",
    )

    val ALL = listOf(WEB_RESEARCH, OPEN_URL, SEARCH_FILES)

    fun available(webToolsEnabled: Boolean, retrieverEnabled: Boolean = true): List<ToolSpec> = buildList {
        if (webToolsEnabled) {
            add(WEB_RESEARCH)
            add(OPEN_URL)
        }
        if (retrieverEnabled) add(SEARCH_FILES)
    }
}

/** System block injected before the user prompt when tools are enabled. */
fun buildToolsSystem(tools: List<ToolSpec>): String {
    if (tools.isEmpty()) return ""

    val hasWebTools = tools.any {
        it.name == AgentTools2.WEB_RESEARCH.name || it.name == AgentTools2.OPEN_URL.name
    }
    val hasRetriever = tools.any { it.name == AgentTools2.SEARCH_FILES.name }

    return buildString {
        append(
            """
            You are an agent. To call a tool, output exactly one block on its own line:
            <tool_call>{"name":"<tool name>","arguments":{...}}</tool_call>
            After a <tool_call>, generation stops and you receive:
            <tool_result>{...}</tool_result>
            Continue from the tool result. Tool results are your working memory for this turn: read them before the next action.
            When you have enough information, give the final answer directly — do not repeat a call you already made.
            Everything outside <tool_call> is shown to the user, so do not narrate private steps.

            WHEN TO USE TOOLS:
            """.trimIndent()
        )
        append('\n')
        append("- For stable general knowledge (math, definitions, concepts), answer directly without any tool.\n")
        if (hasWebTools) {
            append("- For current or factual questions (news, numbers, people, dates, prices, weather, sports, releases), call web_research first.\n")
            append("- Use open_url only to read a specific link more deeply after web_research.\n")
        }
        if (hasRetriever) {
            append("- For attached files, PDFs, documents, or pages already opened in this chat, call search_files before answering.\n")
            append("- For any survival, first-aid, medical, emergency, or practical how-to question, call search_files first and base the answer on its source passages. Include the source title and URL. If no useful passage is found, say so and clearly mark any general-knowledge guidance.\n")
        }
        if (!hasWebTools && hasRetriever) {
            append("- No internet access in this mode. If the local files do not contain the answer, answer from general knowledge and state what is uncertain.\n")
        }
        append("\nAvailable tools:\n")
        tools.forEach { t ->
            append("\n- ").append(t.name).append(" — ").append(t.description)
            append("\n  arguments: ").append(t.arguments)
            append("\n  example: <tool_call>").append(t.example).append("</tool_call>")
        }
        append("\n\nRules:\n")
        append("- One <tool_call> at a time; do not nest tool tags.\n")
        append("- Use as few tool calls as possible — the budget is small. After a tool-limit notice, tools are disabled and you must answer from what you collected.\n")
        append("- Never repeat an identical query; if a result was weak, rewrite the query once, then answer.\n")
        append("- AI answers can be wrong. For urgent danger or medical emergencies, prioritize local emergency services and qualified professionals.\n")
        if (hasWebTools) append("- Do not invent URLs; only open URLs returned by web_research.\n")
    }
}
