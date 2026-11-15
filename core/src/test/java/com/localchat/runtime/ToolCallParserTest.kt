package com.localchat.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness for [ToolCallParser]. Small local models emit slightly-broken JSON;
 * these cases lock in the recovery behaviour we rely on in the agent loop.
 */
class ToolCallParserTest {

    private fun call(body: String) = "<tool_call>$body</tool_call>"

    @Test
    fun `parses a clean tool call`() {
        val c = ToolCallParser.findClosedCall(call("""{"name":"web_research","arguments":{"query":"foo"}}"""))!!
        assertEquals("web_research", c.name)
        assertTrue(c.argumentsJson.contains("\"query\""))
        assertTrue(c.argumentsJson.contains("foo"))
    }

    @Test
    fun `returns null when there is no tool_call tag`() {
        assertNull(ToolCallParser.findClosedCall("just some prose, no tool call here"))
    }

    @Test
    fun `recovers a tool_call with a missing closing tag (small-model behaviour)`() {
        // Qwen3-0.6B emitted exactly this (no </tool_call>) in the desktop harness.
        val c = ToolCallParser.findClosedCall(
            """ok<tool_call>{"name":"search_files","arguments":{"query":"secret"}}""",
        )!!
        assertEquals("search_files", c.name)
        assertTrue(c.argumentsJson.contains("secret"))
    }

    @Test
    fun `recovers from trailing commas`() {
        val c = ToolCallParser.findClosedCall(call("""{"name":"open_url","arguments":{"url":"u",},}"""))!!
        assertEquals("open_url", c.name)
        assertTrue(c.argumentsJson.contains("\"url\""))
    }

    @Test
    fun `recovers from python-style single quotes`() {
        val c = ToolCallParser.findClosedCall(call("""{'name':'web_research','arguments':{'query':'foo'}}"""))!!
        assertEquals("web_research", c.name)
        assertTrue(c.argumentsJson.contains("foo"))
    }

    @Test
    fun `ignores code fences and prose around the object`() {
        val fenced = call("```json\n{\"name\":\"search_files\",\"arguments\":{\"query\":\"q\"}}\n```")
        assertEquals("search_files", ToolCallParser.findClosedCall(fenced)!!.name)

        val prosey = call("Sure, calling the tool: {\"name\":\"open_url\",\"arguments\":{\"url\":\"u\"}} now.")
        assertEquals("open_url", ToolCallParser.findClosedCall(prosey)!!.name)
    }

    @Test
    fun `unwraps arguments delivered as a stringified json blob`() {
        val c = ToolCallParser.findClosedCall(call("""{"name":"web_research","arguments":"{\"query\":\"v\"}"}"""))!!
        assertEquals("web_research", c.name)
        assertTrue("args should be unwrapped json: ${c.argumentsJson}", c.argumentsJson.contains("\"query\""))
    }

    @Test
    fun `missing arguments yields empty object`() {
        val c = ToolCallParser.findClosedCall(call("""{"name":"web_research"}"""))!!
        assertEquals("web_research", c.name)
        assertEquals("{}", c.argumentsJson)
    }

    @Test
    fun `missing name yields null`() {
        assertNull(ToolCallParser.findClosedCall(call("""{"arguments":{"query":"x"}}""")))
    }

    @Test
    fun `picks the first closed call when several are present`() {
        val text = call("""{"name":"web_research","arguments":{"query":"a"}}""") +
            "\n" + call("""{"name":"open_url","arguments":{"url":"b"}}""")
        assertEquals("web_research", ToolCallParser.findClosedCall(text)!!.name)
    }

    @Test
    fun `handles unicode tool names and arguments`() {
        val c = ToolCallParser.findClosedCall(call("""{"name":"web_research","arguments":{"query":"погода Токио"}}"""))!!
        assertEquals("web_research", c.name)
        assertTrue(c.argumentsJson.contains("Токио"))
    }
}
