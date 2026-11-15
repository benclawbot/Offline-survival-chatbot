package com.localchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Harness for per-family tool dialects (Hermes <tool_call> vs Gemma ```tool_code). */
class ToolDialectTest {

    @Test
    fun `family detection picks Gemma for gemma models, Hermes otherwise`() {
        assertTrue(ToolDialect.forModel("gemma-4-e2b", "Gemma 4 E2B") is ToolDialect.Gemma)
        assertTrue(ToolDialect.forModel("google/gemma3-1b", "Gemma3 1B") is ToolDialect.Gemma)
        assertTrue(ToolDialect.forModel("qwen3-0.6b", "Qwen3 0.6B") is ToolDialect.Hermes)
        assertTrue(ToolDialect.forModel("phi-4-mini", "Phi-4") is ToolDialect.Hermes)
    }

    // ---- Hermes ----------------------------------------------------------

    @Test
    fun `hermes parses a tool_call and renders a tool_result`() {
        val call = ToolDialect.Hermes.parseCall(
            """ok<tool_call>{"name":"web_research","arguments":{"query":"x"}}</tool_call>""",
        )!!
        assertEquals("web_research", call.name)
        val result = ToolDialect.Hermes.renderResult("web_research", call.argumentsJson, "2 results", true, """{"a":1}""", "[hint]")
        assertTrue(result.contains("<tool_result>"))
        assertTrue(result.contains("summary:"))
    }

    @Test
    fun `hermes strips tool markup from visible text`() {
        val visible = ToolDialect.Hermes.stripMarkup("before <tool_call>{\"name\":\"x\"}</tool_call> after")
        assertFalse(visible.contains("tool_call"))
        assertTrue(visible.contains("before"))
        assertTrue(visible.contains("after"))
    }

    // ---- Gemma -----------------------------------------------------------

    @Test
    fun `gemma parses a tool_code python call into name and json args`() {
        val text = "Sure.\n```tool_code\nweb_research(query=\"current Tokyo weather\")\n```"
        val call = ToolDialect.Gemma.parseCall(text)!!
        assertEquals("web_research", call.name)
        assertTrue(call.argumentsJson.contains("\"query\""))
        assertTrue(call.argumentsJson.contains("current Tokyo weather"))
    }

    @Test
    fun `gemma parses multiple args with types`() {
        val call = ToolDialect.Gemma.parseCall("```tool_code\nsearch_files(query=\"auth\", top_k=3)\n```")!!
        assertEquals("search_files", call.name)
        assertTrue(call.argumentsJson.contains("\"auth\""))
        assertTrue(call.argumentsJson.contains("3"))
    }

    @Test
    fun `gemma recovers an unclosed tool_code fence`() {
        // Small models may omit the closing ``` — still parse once the call is complete.
        val call = ToolDialect.Gemma.parseCall("```tool_code\nweb_research(query=\"kotlin version\")")!!
        assertEquals("web_research", call.name)
        assertTrue(call.argumentsJson.contains("kotlin version"))
    }

    @Test
    fun `gemma renders tool_output and strips its markup`() {
        val result = ToolDialect.Gemma.renderResult("web_research", """{"query":"x"}""", "ok", true, """{"a":1}""", "[hint]")
        assertTrue(result.contains("```tool_output"))
        val visible = ToolDialect.Gemma.stripMarkup("answer ```tool_code\nfoo(x=1)\n``` tail")
        assertFalse(visible.contains("tool_code"))
        assertTrue(visible.contains("answer"))
        assertTrue(visible.contains("tail"))
    }

    @Test
    fun `gemma protocol lists tools as python signatures`() {
        val proto = ToolDialect.Gemma.protocol(AgentTools2.ALL)
        assertTrue(proto.contains("```tool_code"))
        assertTrue(proto.contains("def web_research"))
        assertTrue(proto.contains("def search_files"))
    }

    @Test
    fun `no tool markup yields null`() {
        assertNull(ToolDialect.Gemma.parseCall("just an answer, no tool"))
        assertNull(ToolDialect.Hermes.parseCall("just an answer, no tool"))
    }
}
