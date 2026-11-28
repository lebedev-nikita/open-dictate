package com.opendictate.app.network

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantFunction
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.McpServer
import com.opendictate.app.model.McpTool
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAgentTest {
    private val notes = McpServer("a", "Notes", "https://notes.example/mcp").withTools(listOf(
        McpTool("search", "Finds a note", """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""),
        McpTool("create", "Creates a note", """{"type":"object","properties":{"text":{"type":"string"},"folder":{"type":"string","enum":["inbox","work"]}},"required":["text"]}"""),
    ))
    private val functions = AssistantCommand.functions(listOf(notes))

    /** A model that answers each request with the next of [turns], recording what it was sent. */
    private class Model(vararg turns: Either<DictationError, JSONObject>) {
        private val pending = ArrayDeque(turns.toList())
        val requests: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())

        suspend fun send(body: JSONObject): Either<DictationError, JSONObject> {
            requests += body
            return pending.removeFirstOrNull() ?: DictationError.Unexpected.left()
        }
    }

    private fun agent(model: Model, callTool: suspend (AssistantFunction, String) -> Either<McpError, McpCallResult>) =
        AssistantAgent(functions, "gpt-6-luna", model::send, callTool)

    private fun run(model: Model, command: String = "команда", callTool: suspend (AssistantFunction, String) -> Either<McpError, McpCallResult>) =
        runBlocking { withTimeout(10_000) { agent(model, callTool).run(command) } }

    @Test
    fun callsOfOneTurnRunTogetherAndTheirAnswersReturnToTheModel() {
        val model = Model(
            response(reasoning(), call("c1", "s1_search", """{"query":"молоко"}"""), call("c2", "s1_search", """{"query":"хлеб"}""")),
            response(message("Нашёл обе заметки.")),
        )
        val started = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val result = run(model, "найди заметки про молоко и хлеб") { _, arguments ->
            if (started.incrementAndGet() == 2) bothStarted.complete(Unit)
            // Each call waits for the other: sequential calls would never get past this line.
            bothStarted.await()
            McpCallResult("note about " + JSONObject(arguments).getString("query"), isError = false).right()
        }

        assertEquals("Нашёл обе заметки.", result.reply.getOrNull())
        assertEquals(2, result.calls.size)
        assertTrue(result.calls.none { it.isError })

        val first = model.requests[0]
        assertEquals("gpt-6-luna", first.getString("model"))
        assertFalse(first.getBoolean("store"))
        assertTrue(first.getBoolean("parallel_tool_calls"))
        assertEquals("low", first.getJSONObject("reasoning").getString("effort"))
        assertEquals("reasoning.encrypted_content", first.getJSONArray("include").getString(0))
        val tools = first.getJSONArray("tools")
        assertEquals(2, tools.length())
        val create = (0 until tools.length()).map(tools::getJSONObject).single { it.getString("name") == "s1_create" }
        assertEquals("[Notes] Creates a note", create.getString("description"))
        assertFalse(create.getBoolean("strict"))
        // The server's schema reaches the model as written, enum included.
        assertEquals(JSONArray("""["inbox","work"]""").toString(),
            create.getJSONObject("parameters").getJSONObject("properties").getJSONObject("folder").getJSONArray("enum").toString())
        assertTrue(items(first).any { it.optString("role") == "developer" && it.toString().contains("Call independent tools together") })
        assertTrue(items(first).any { it.optString("role") == "user" && it.toString().contains("найди заметки про молоко и хлеб") })

        val outputs = items(model.requests[1]).filter { it.getString("type") == "function_call_output" }
        assertEquals(mapOf("c1" to "note about молоко", "c2" to "note about хлеб"), outputs.associate { it.getString("call_id") to it.getString("output") })
        // The reasoning comes back encrypted, since the API keeps nothing between turns.
        assertTrue(items(model.requests[1]).any { it.getString("type") == "reasoning" && it.optString("encrypted_content") == "enc" })
    }

    @Test
    fun aCallThatNeedsAnAnswerWaitsForTheNextTurn() {
        val model = Model(
            response(call("c1", "s1_search", """{"query":"список покупок"}""")),
            response(call("c2", "s1_create", """{"text":"копия: молоко","folder":"work"}""")),
            response(message("Скопировал список в work.")),
        )
        val seen = mutableListOf<String>()
        val result = run(model) { function, arguments ->
            seen += function.tool.name + " " + arguments
            McpCallResult(if (function.tool.name == "search") "молоко" else """{"link":"https://notes.example/n/2"}""", isError = false).right()
        }

        assertEquals(listOf("""search {"query":"список покупок"}""", """create {"text":"копия: молоко","folder":"work"}"""), seen)
        assertEquals(3, model.requests.size)
        assertEquals("молоко", items(model.requests[1]).single { it.getString("type") == "function_call_output" }.getString("output"))
        assertEquals("https://notes.example/n/2", result.calls.last().answer.link)
        assertEquals("Скопировал список в work.", result.reply.getOrNull())
    }

    @Test
    fun anErrorAnswerReturnsToTheModelAsAFailedCall() {
        val model = Model(
            response(call("c1", "s1_create", """{"text":"x"}""")),
            response(message("Сервер отказал: нет папки.")),
        )
        val result = run(model) { _, _ -> McpCallResult("""{"message":"folder required"}""", isError = true).right() }

        val output = items(model.requests[1]).single { it.getString("type") == "function_call_output" }.getString("output")
        assertTrue(output.contains("folder required"))
        assertTrue(result.calls.single().isError)
        assertEquals("folder required", result.calls.single().answer.text)
        assertEquals("Сервер отказал: нет папки.", result.reply.getOrNull())
    }

    @Test
    fun anUnreachableServerIsReportedToTheModelAndKept() {
        val model = Model(response(call("c1", "s1_search", """{"query":"x"}""")), response(message("Нужно войти заново.")))
        val result = run(model) { _, _ -> McpError.SignInNeeded.left() }

        assertEquals(McpError.SignInNeeded, result.calls.single().error)
        val output = items(model.requests[1]).single { it.getString("type") == "function_call_output" }.getString("output")
        assertTrue(output.contains("sign in again"))
    }

    @Test
    fun aFailedModelRequestKeepsTheCallsAlreadyMade() {
        val model = Model(response(call("c1", "s1_create", """{"text":"x"}""")), DictationError.Http(500).left())
        val result = run(model) { _, _ -> McpCallResult("ok", isError = false).right() }

        assertEquals(DictationError.Http(500), result.reply.leftOrNull())
        assertEquals(1, result.calls.size)
        assertEquals(DictationError.Timeout, run(Model(DictationError.Timeout.left())) { _, _ -> error("no call") }.reply.leftOrNull())
    }

    @Test
    fun wordsWithoutCallsAreTheReply() {
        val result = run(Model(response(message("Нет подходящего инструмента.")))) { _, _ -> error("no call") }
        assertEquals("Нет подходящего инструмента.", result.reply.getOrNull())
        assertTrue(result.calls.isEmpty())
    }

    @Test
    fun aModelThatKeepsCallingStopsAfterTheLastRound() {
        val turns = (1..AssistantCommand.MAX_ROUNDS + 2).map { response(call("c$it", "s1_search", """{"query":"$it"}""")) }
        val model = Model(*turns.toTypedArray())
        val result = run(model) { _, _ -> McpCallResult("ещё", isError = false).right() }

        assertEquals(AssistantCommand.MAX_ROUNDS, result.calls.size)
        assertEquals(AssistantCommand.MAX_ROUNDS + 1, model.requests.size)
        assertEquals("", result.reply.getOrNull())
    }

    @Test
    fun callsBeyondTheLimitNeverReachAServer() {
        val calls = (1..AssistantCommand.MAX_CALLS + 3).map { call("c$it", "s1_create", """{"text":"$it"}""") }
        val model = Model(response(*calls.toTypedArray()), response(message("Создал часть заметок.")))
        val made = AtomicInteger()
        val result = run(model) { _, _ -> McpCallResult("ok", isError = false).right().also { made.incrementAndGet() } }

        assertEquals(AssistantCommand.MAX_CALLS, made.get())
        assertEquals(AssistantCommand.MAX_CALLS, result.calls.size)
        val outputs = items(model.requests[1]).filter { it.getString("type") == "function_call_output" }
        assertEquals(AssistantCommand.MAX_CALLS + 3, outputs.size)
        assertEquals(3, outputs.count { it.getString("output").contains("no more tool calls") })
    }

    @Test
    fun koogLogsGoNowhere() {
        // Koog logs prompts, tool arguments and answers through SLF4J; slf4j-simple would print them.
        assertTrue(runCatching { Class.forName("org.slf4j.simple.SimpleServiceProvider") }.isFailure)
    }

    private fun items(request: JSONObject): List<JSONObject> {
        val input = request.getJSONArray("input")
        return (0 until input.length()).map(input::getJSONObject)
    }

    private fun response(vararg items: String): Either<DictationError, JSONObject> = JSONObject(
        """{"id":"resp","created_at":1,"model":"gpt-6-luna","parallel_tool_calls":true,"status":"completed",""" +
            """"text":{"format":{"type":"text"}},"output":[${items.joinToString(",")}]}""",
    ).right()

    private fun call(id: String, name: String, arguments: String) =
        """{"type":"function_call","id":"fc_$id","call_id":"$id","name":"$name","arguments":${JSONObject.quote(arguments)},"status":"completed"}"""

    private fun message(text: String) =
        """{"type":"message","id":"msg","role":"assistant","status":"completed","content":[{"type":"output_text","text":${JSONObject.quote(text)},"annotations":[]}]}"""

    private fun reasoning() = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"enc"}"""
}
