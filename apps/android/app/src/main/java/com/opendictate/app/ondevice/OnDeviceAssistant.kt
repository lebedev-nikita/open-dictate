package com.opendictate.app.ondevice

import arrow.core.Either
import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.common.FunctionCall
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.schema.tools.FunctionSchema
import com.google.mlkit.genai.schema.tools.FunctionType
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantDecision
import com.opendictate.app.model.AssistantFunction
import com.opendictate.app.model.DictationError
import com.opendictate.app.model.FailedCall
import org.json.JSONArray
import org.json.JSONObject

/** Spoken commands answered by Gemini Nano: the same single tool choice as [AssistantCommand]. */
object OnDeviceAssistant {
    /** [failed] is a call the server refused; Nano sees its error once, as the OpenAI model does. */
    suspend fun decide(command: String, functions: List<AssistantFunction>, failed: FailedCall? = null): Either<DictationError, AssistantDecision> =
        GeminiNano.respond(
            AssistantCommand.instructions(functions),
            if (failed == null) command else "$command\n\n${AssistantCommand.correction(failed)}",
            tools = functions.map(::declaration),
        ).map { decision(it.text, it.functionCalls, functions) }

    fun declaration(function: AssistantFunction): FunctionDeclaration = FunctionDeclaration(
        function.name,
        AssistantCommand.description(function),
        schema(runCatching { JSONObject(function.tool.schemaJson) }.getOrNull() ?: JSONObject()).let { root ->
            if (root.type == FunctionType.OBJECT) root else FunctionSchema(FunctionType.OBJECT, properties = emptyMap())
        },
    )

    /** Converts the JSON Schema subset that function declarations accept; other keywords are dropped. */
    fun schema(json: JSONObject): FunctionSchema {
        val type = when (json.opt("type").let { if (it is JSONArray) (0 until it.length()).map(it::optString).firstOrNull { name -> name != "null" } else it }) {
            "string" -> FunctionType.STRING
            "number" -> FunctionType.NUMBER
            "integer" -> FunctionType.INTEGER
            "boolean" -> FunctionType.BOOLEAN
            "array" -> FunctionType.ARRAY
            "object" -> FunctionType.OBJECT
            else -> if (json.has("properties")) FunctionType.OBJECT else FunctionType.STRING
        }
        val properties = json.optJSONObject("properties")?.let { raw ->
            raw.keys().asSequence().mapNotNull { key -> raw.optJSONObject(key)?.let { key to schema(it) } }.toMap()
        }
        return FunctionSchema(
            type = type,
            description = json.optString("description").takeIf(String::isNotBlank)?.take(MAX_DESCRIPTION),
            properties = properties ?: if (type == FunctionType.OBJECT) emptyMap() else null,
            required = json.optJSONArray("required")?.strings()?.filter { properties?.containsKey(it) == true },
            enum = json.optJSONArray("enum")?.strings()?.takeIf { type == FunctionType.STRING },
            items = json.optJSONObject("items")?.let(::schema),
        )
    }

    fun decision(text: String?, calls: List<FunctionCall>, functions: List<AssistantFunction>): AssistantDecision {
        calls.forEach { call ->
            val function = functions.firstOrNull { it.name == call.name } ?: return@forEach
            return AssistantDecision.Call(function, JSONObject(call.arguments.mapValues { json(it.value) }).toString())
        }
        val reply = text.orEmpty().trim()
        return if (reply.isEmpty()) AssistantDecision.Nothing else AssistantDecision.Reply(reply)
    }

    private fun json(value: FunctionArgValue): Any = when (value) {
        is FunctionArgValue.StringValue -> value.value
        is FunctionArgValue.BoolValue -> value.value
        is FunctionArgValue.IntValue -> value.value
        is FunctionArgValue.LongValue -> value.value
        is FunctionArgValue.DoubleValue -> value.value
        is FunctionArgValue.ListValue -> JSONArray(value.values.map(::json))
        is FunctionArgValue.MapValue -> JSONObject(value.map.mapValues { json(it.value) })
        else -> JSONObject.NULL
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }

    private const val MAX_DESCRIPTION = 1_000
}
