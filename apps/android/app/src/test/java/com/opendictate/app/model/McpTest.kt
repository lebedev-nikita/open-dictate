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
    fun requestOffersEnabledToolsAndAsksForOneCall() {
        val functions = AssistantCommand.functions(listOf(code.copy(enabledTools = setOf("t3_thread_launch"))))
        val request = AssistantCommand.request("Запусти тред про тесты", functions, "gpt-6-luna")
        assertEquals("gpt-6-luna", request.getString("model"))
        assertEquals("Запусти тред про тесты", request.getString("input"))
        assertFalse(request.getBoolean("store"))
        assertFalse(request.getBoolean("parallel_tool_calls"))
        assertEquals(1, request.getJSONArray("tools").length())
        val offered = request.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", offered.getString("type"))
        assertEquals("s1_t3_thread_launch", offered.getString("name"))
        assertEquals("[T3 Code] Does t3_thread_launch", offered.getString("description"))
        assertFalse(offered.getBoolean("strict"))
        assertEquals("string", offered.getJSONObject("parameters").getJSONObject("properties").getJSONObject("prompt").getString("type"))
    }

    @Test
    fun aToolWithoutASchemaStillGetsAnObjectSchema() {
        val server = McpServer("e", "Bare", "https://bare.example/mcp").withTools(listOf(McpTool("ping", "", "")))
        val parameters = AssistantCommand.request("ping", AssistantCommand.functions(listOf(server)), "m")
            .getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
    }

    @Test
    fun aFunctionCallBecomesACallOfItsTool() {
        val functions = AssistantCommand.functions(listOf(code, home))
        val response = JSONObject("""{"output":[{"type":"reasoning"},{"type":"function_call","call_id":"c1","name":"s2_light_turn_on","arguments":"{\"prompt\":\"кухня\"}"}]}""")
        val decision = AssistantCommand.decision(response, functions) as AssistantDecision.Call
        assertEquals("light.turn_on", decision.function.tool.name)
        assertEquals("Home", decision.function.server.name)
        assertEquals("кухня", JSONObject(decision.argumentsJson).getString("prompt"))
    }

    @Test
    fun wordsWithoutACallAreAReply() {
        val response = JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Нет подходящего инструмента."}]}]}""")
        assertEquals(AssistantDecision.Reply("Нет подходящего инструмента."), AssistantCommand.decision(response, emptyList()))
    }

    @Test
    fun anUnknownFunctionOrAnEmptyAnswerIsNothing() {
        val unknown = JSONObject("""{"output":[{"type":"function_call","name":"s9_other","arguments":"{}"}]}""")
        assertEquals(AssistantDecision.Nothing, AssistantCommand.decision(unknown, AssistantCommand.functions(listOf(code))))
        assertEquals(AssistantDecision.Nothing, AssistantCommand.decision(JSONObject("{}"), emptyList()))
    }
}
