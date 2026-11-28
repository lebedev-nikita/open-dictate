package com.opendictate.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTest {
    private fun tool(name: String, description: String = "Does $name") =
        McpTool(name, description, """{"type":"object","properties":{"prompt":{"type":"string"}},"required":["prompt"]}""")

    private val code = McpServer("a", "T3 Code", "https://macbook.t3.home.arpa/mcp", McpAuth.BROWSER, trustDeviceCertificates = true)
        .withTools(listOf(tool("t3_thread_launch"), tool("t3_thread_list")))
    private val home = McpServer("b", "Home", "https://home.example/mcp", McpAuth.TOKEN)
        .withTools(listOf(tool("light.turn_on"), tool("t3_thread_list")))

    @Test
    fun serversSurviveStorage() {
        assertEquals(listOf(code, home), McpServer.listFromJson(McpServer.listToJson(listOf(code, home))))
        assertEquals(emptyList<McpServer>(), McpServer.listFromJson(null))
        assertEquals(emptyList<McpServer>(), McpServer.listFromJson("not json"))
    }

    @Test
    fun onlyHttpsAddressesAreAccepted() {
        assertTrue(McpServer.isValidUrl("https://macbook.t3.home.arpa/mcp"))
        assertFalse(McpServer.isValidUrl("http://192.168.1.2/mcp"))
        assertFalse(McpServer.isValidUrl("macbook.t3.home.arpa/mcp"))
        assertFalse(McpServer.isValidUrl(""))
    }

    @Test
    fun aShortFirstToolListIsEnabledWhole() {
        assertEquals(setOf("t3_thread_launch", "t3_thread_list"), code.enabledTools)
    }

    @Test
    fun aLongFirstToolListStartsDisabled() {
        // T3 Code offers sixty tools, and every enabled one is sent with each command.
        val many = McpServer("c", "Many", "https://many.example/mcp").withTools((1..60).map { tool("tool_$it") })
        assertEquals(emptySet<String>(), many.enabledTools)
        assertEquals(60, many.tools.size)
    }

    @Test
    fun refreshingKeepsChoicesForToolsThatRemain() {
        val chosen = code.copy(enabledTools = setOf("t3_thread_launch"))
        val refreshed = chosen.withTools(listOf(tool("t3_thread_launch"), tool("t3_thread_send")))
        assertEquals(setOf("t3_thread_launch"), refreshed.enabledTools)
        assertEquals(listOf("t3_thread_launch", "t3_thread_send"), refreshed.tools.map { it.name })
    }

    @Test
    fun functionNamesAreSafeAndDistinctAcrossServers() {
        val functions = AssistantCommand.functions(listOf(code, home))
        assertEquals(listOf("s1_t3_thread_launch", "s1_t3_thread_list", "s2_light_turn_on", "s2_t3_thread_list"), functions.map { it.name })
        assertEquals("light.turn_on", functions[2].tool.name)
        assertEquals(home, functions[2].server)
    }

    @Test
    fun longAndClashingNamesStayWithinTheLimit() {
        val long = "x".repeat(80)
        val server = McpServer("d", "Long", "https://long.example/mcp").withTools(listOf(tool("$long.a"), tool("$long.b")))
        val names = AssistantCommand.functions(listOf(server)).map { it.name }
        assertEquals(2, names.toSet().size)
        assertTrue(names.all { it.length <= 64 && Regex("[A-Za-z0-9_-]+").matches(it) })
    }

    @Test
    fun aToolWithoutASchemaStillGetsAnObjectSchema() {
        val parameters = AssistantCommand.parameters("")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        val written = AssistantCommand.parameters("""{"properties":{"room":{"type":"string","enum":["кухня","спальня"]}}}""")
        assertEquals("object", written.getString("type"))
        assertEquals(2, written.getJSONObject("properties").getJSONObject("room").getJSONArray("enum").length())
    }

    @Test
    fun functionToolsAreNotStrictUnlessTheySaySo() {
        val request = JSONObject("""{"tools":[{"type":"function","name":"a"},{"type":"function","name":"b","strict":true},{"type":"web_search"}]}""")
        val tools = AssistantCommand.withLenientTools(request).getJSONArray("tools")
        assertFalse(tools.getJSONObject(0).getBoolean("strict"))
        assertTrue(tools.getJSONObject(1).getBoolean("strict"))
        assertFalse(tools.getJSONObject(2).has("strict"))
        assertEquals(JSONObject("{}").toString(), AssistantCommand.withLenientTools(JSONObject("{}")).toString())
    }

    @Test
    fun aLongToolAnswerIsCutForTheModel() {
        assertEquals(8_000, AssistantCommand.toolOutput("x".repeat(20_000)).length)
        assertEquals("(empty answer)", AssistantCommand.toolOutput(" "))
        assertEquals("ok", AssistantCommand.toolOutput("ok"))
    }

    @Test
    fun theUsersNotesAboutServersReachTheModel() {
        val noted = code.copy(hint = "When no project is named, pass scratch: true.")
        val instructions = AssistantCommand.instructions(AssistantCommand.functions(listOf(noted, home)))
        assertTrue(instructions.endsWith("- T3 Code: When no project is named, pass scratch: true."))
        assertFalse(AssistantCommand.instructions(AssistantCommand.functions(listOf(code))).contains("Notes from the user"))
        assertEquals(noted, McpServer.listFromJson(McpServer.listToJson(listOf(noted))).single())
    }

    @Test
    fun answersAreReducedForANotification() {
        assertEquals(ToolAnswer("Свет включён", null), AssistantCommand.answer(" Свет включён "))
        assertEquals(ToolAnswer("Pass projectId: this MCP client is not running inside a T3 thread.", null),
            AssistantCommand.answer("""{"_tag":"OrchestratorMcpFailure","code":"target_required","message":"Pass projectId: this MCP client is not running inside a T3 thread."}"""))
        assertEquals(ToolAnswer(null, "https://app.t3.codes/threads/a/b"),
            AssistantCommand.answer("""{"threadId":"mcp:1","link":"https://app.t3.codes/threads/a/b","status":"preparing"}"""))
        // Only https links are opened from a notification.
        assertEquals(ToolAnswer(null, null), AssistantCommand.answer("""{"url":"intent://evil","ok":true}"""))
        assertEquals(ToolAnswer(null, null), AssistantCommand.answer(""))
    }
}
