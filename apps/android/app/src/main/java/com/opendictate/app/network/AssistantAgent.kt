package com.opendictate.app.network

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject as KoogJSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.toKotlinxJsonObject
import ai.koog.serialization.typeToken
import arrow.core.Either
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantFunction
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.ToolAnswer
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.json.JSONObject

/** How one tool call of a spoken command ended; [error] is set when its server could not be used. */
data class ToolCallOutcome(val function: AssistantFunction, val answer: ToolAnswer, val isError: Boolean, val error: McpError? = null)

/** What a command came to: every tool call, in the order they ended, and the model's closing words. */
data class AssistantRun(val calls: List<ToolCallOutcome>, val reply: Either<DictationError, String>)

/**
 * Runs a spoken command as a Koog agent over the user's MCP tools. Koog keeps the conversation,
 * turns the model's calls into tool executions, runs the calls of one turn in parallel and sends
 * their answers back for the next turn, until the model answers in words.
 *
 * Koog's OpenAI client builds the Responses requests and reads the answers; [send] carries them,
 * so a command uses the same route as the rest of the text model: the API key or the ChatGPT plan.
 */
internal class AssistantAgent(
    private val functions: List<AssistantFunction>,
    private val modelId: String,
    private val send: suspend (JSONObject) -> Either<DictationError, JSONObject>,
    private val callTool: suspend (AssistantFunction, String) -> Either<McpError, McpCallResult>,
) {
    suspend fun run(command: String): AssistantRun {
        val calls = Calls()
        val client = OpenAILLMClient(
            // The transport authorizes each request itself, with a key or a plan token.
            apiKey = "",
            settings = OpenAIClientSettings(),
            httpClientFactory = TransportFactory(send),
            toolsConverter = ServerSchemas(functions),
        )
        val agent = AIAgent(
            promptExecutor = MultiLLMPromptExecutor(mapOf(LLMProvider.OpenAI to client)),
            agentConfig = AIAgentConfig(
                prompt = prompt("spoken-command", PARAMS) { system(AssistantCommand.instructions(functions)) },
                model = LLModel(LLMProvider.OpenAI, modelId, CAPABILITIES),
                maxAgentIterations = MAX_ITERATIONS,
            ),
            strategy = STRATEGY,
            toolRegistry = ToolRegistry.builder().tools(functions.map { ServerTool(it, calls) }).build(),
        )
        val reply = Either.catch { agent.run(command) }.mapLeft { error ->
            if (error is CancellationException) throw error
            generateSequence(error) { it.cause }.firstNotNullOfOrNull { (it as? ModelRequestFailed)?.error }
                ?: DictationError.InvalidResponse
        }
        return AssistantRun(calls.all(), reply)
    }

    /** The calls of one command, filled from parallel tool executions. */
    private class Calls {
        private val mutex = Mutex()
        private val outcomes = mutableListOf<ToolCallOutcome>()
        private var started = 0

        /** Reserves a call; false once the command has used up [AssistantCommand.MAX_CALLS]. */
        suspend fun start(): Boolean = mutex.withLock { (started < AssistantCommand.MAX_CALLS).also { if (it) started++ } }

        suspend fun add(outcome: ToolCallOutcome) = mutex.withLock { outcomes += outcome }

        suspend fun all(): List<ToolCallOutcome> = mutex.withLock { outcomes.toList() }
    }

    /**
     * One MCP tool as Koog sees it. Arguments pass through as the model wrote them; an error answer
     * is a [ToolException], which Koog returns to the model as a failed call without logging it.
     */
    private inner class ServerTool(private val function: AssistantFunction, private val calls: Calls) : Tool<KoogJSONObject, String>(
        argsType = typeToken<KoogJSONObject>(),
        resultType = typeToken<String>(),
        descriptor = ToolDescriptor(function.name, AssistantCommand.description(function)),
    ) {
        override fun decodeArgs(rawArgs: KoogJSONObject, serializer: JSONSerializer): KoogJSONObject = rawArgs

        override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement = JSONPrimitive.of(result)

        override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result

        override suspend fun execute(args: KoogJSONObject): String {
            if (!calls.start()) throw ToolException.ValidationFailure(LIMIT_REACHED)
            val arguments = args.toKotlinxJsonObject().toString()
            return callTool(function, arguments).fold(
                { error ->
                    calls.add(ToolCallOutcome(function, ToolAnswer(null, null), isError = true, error = error))
                    throw ToolException.ValidationFailure(unavailable(error))
                },
                { result ->
                    calls.add(ToolCallOutcome(function, AssistantCommand.answer(result.text), result.isError))
                    val output = AssistantCommand.toolOutput(result.text)
                    if (result.isError) throw ToolException.ValidationFailure(output)
                    output
                },
            )
        }
    }

    /** Sends each tool's schema as its server wrote it; Koog's own descriptors cannot express all of JSON Schema. */
    private class ServerSchemas(functions: List<AssistantFunction>) : OpenAICompatibleToolDescriptorSchemaGenerator() {
        private val schemas = functions.associate { it.name to it.tool.schemaJson }

        override fun generate(toolDescriptor: ToolDescriptor): JsonObject {
            val schema = schemas[toolDescriptor.name] ?: return super.generate(toolDescriptor)
            return Json.parseToJsonElement(AssistantCommand.parameters(schema).toString()).jsonObject
        }
    }

    private class TransportFactory(private val send: suspend (JSONObject) -> Either<DictationError, JSONObject>) : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = Transport(clientName, json, send)
    }

    /** Carries Koog's Responses requests; nothing else in Koog's client is used. */
    private class Transport(
        override val clientName: String,
        private val json: Json,
        private val send: suspend (JSONObject) -> Either<DictationError, JSONObject>,
    ) : KoogHttpClient {
        @OptIn(InternalSerializationApi::class)
        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): R {
            val body = requestBody as? String ?: throw UnsupportedOperationException("Only serialized requests are sent")
            val response = send(AssistantCommand.withLenientTools(JSONObject(body))).fold({ throw ModelRequestFailed(it) }, { it })
            return json.decodeFromString(responseType.serializer(), response.toString())
        }

        override suspend fun <R : Any> get(path: String, responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>): R =
            throw UnsupportedOperationException()

        override fun <T : Any, R : Any, O : Any> sse(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            dataFilter: (String?) -> Boolean,
            decodeStreamingResponse: (String) -> R,
            processStreamingChunk: (R) -> O?,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<O> = throw UnsupportedOperationException()

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): Flow<String> = throw UnsupportedOperationException()

        override fun close() = Unit
    }

    /** A model request that failed in the app's transport, carried through Koog to [run]. */
    private class ModelRequestFailed(val error: DictationError) : Exception()

    private companion object {
        const val MAX_ITERATIONS = 50
        const val LIMIT_REACHED = "The app allows no more tool calls for this command."

        val CAPABILITIES = listOf(LLMCapability.Tools, LLMCapability.Thinking, LLMCapability.OpenAIEndpoint.Responses)

        // Without stored responses, reasoning carries over between turns only in encrypted form.
        val PARAMS = OpenAIResponsesParams(
            toolChoice = LLMParams.ToolChoice.Auto,
            parallelToolCalls = true,
            reasoning = ReasoningConfig(effort = ReasoningEffort.LOW),
            include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT),
            store = false,
        )

        /**
         * Asks the model, runs every call of its turn in parallel, and sends the answers back. A call
         * that needs another's answer waits for the next turn. After [AssistantCommand.MAX_ROUNDS]
         * turns, calls the model still makes are not run.
         */
        val STRATEGY = functionalStrategy<String, String>("spoken-command") { command ->
            var response = requestLLM(command)
            var rounds = 0
            while (rounds < AssistantCommand.MAX_ROUNDS) {
                val toolCalls = getToolCalls(response)
                if (toolCalls.isEmpty()) break
                response = sendToolResults(executeTools(toolCalls, parallelTools = true))
                rounds++
            }
            getTextParts(response).joinToString("") { it.text }.trim()
        }

        fun unavailable(error: McpError): String = when (error) {
            McpError.SignInNeeded -> "The server needs the user to sign in again in the app's settings."
            is McpError.Unreachable -> "The server could not be reached."
            is McpError.Failed -> "The server could not carry out the call."
        }
    }
}
