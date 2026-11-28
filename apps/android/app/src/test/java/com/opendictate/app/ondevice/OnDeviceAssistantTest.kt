package com.opendictate.app.ondevice

import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.common.FunctionCall
import com.google.mlkit.genai.schema.tools.FunctionType
import com.opendictate.app.model.AssistantCommand
import com.opendictate.app.model.AssistantDecision
import com.opendictate.app.model.McpServer
import com.opendictate.app.model.McpTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceAssistantTest {
    private val schema = """{"type":"object","properties":{
        "title":{"type":"string","description":"Task title"},
        "priority":{"type":["integer","null"]},
        "labels":{"type":"array","items":{"type":"string","enum":["home","work"]}},
        "due":{"type":"object","properties":{"date":{"type":"string"}}}
    },"required":["title","missing"],"additionalProperties":false}"""
    private val server = McpServer("a", "Todo", "https://todo.example/mcp")
        .withTools(listOf(McpTool("add.task", "Adds a task", schema)))
    private val functions = AssistantCommand.functions(listOf(server))

    @Test fun `tool schemas keep types, descriptions, nesting and known required fields`() {
        val declaration = OnDeviceAssistant.declaration(functions.single())
        assertEquals("s1_add_task", declaration.name)
        assertEquals("[Todo] Adds a task", declaration.description)
        val parameters = requireNotNull(declaration.parameters)
        assertEquals(FunctionType.OBJECT, parameters.type)
        assertEquals(listOf("title"), parameters.required)
        val properties = requireNotNull(parameters.properties)
        assertEquals(FunctionType.STRING, properties.getValue("title").type)
        assertEquals("Task title", properties.getValue("title").description)
        assertEquals(FunctionType.INTEGER, properties.getValue("priority").type)
        assertEquals(listOf("home", "work"), properties.getValue("labels").items?.enum)
        assertEquals(FunctionType.STRING, properties.getValue("due").properties?.getValue("date")?.type)
    }

    @Test fun `a tool without a usable schema takes no arguments`() {
        val bare = McpServer("b", "Lights", "https://lights.example/mcp").withTools(listOf(McpTool("toggle", "Toggles", "not json")))
        val parameters = requireNotNull(OnDeviceAssistant.declaration(AssistantCommand.functions(listOf(bare)).single()).parameters)
        assertEquals(FunctionType.OBJECT, parameters.type)
        assertEquals(emptyMap<String, Any>(), parameters.properties)
        assertNull(parameters.required)
    }

    @Test fun `a function call becomes the tool call with JSON arguments`() {
        val call = FunctionCall("s1_add_task", mapOf(
            "title" to FunctionArgValue.StringValue("Купить молоко"),
            "priority" to FunctionArgValue.IntValue(2),
            "labels" to FunctionArgValue.ListValue(listOf(FunctionArgValue.StringValue("home"))),
        ), "call-1")
        val decision = OnDeviceAssistant.decision(null, listOf(call), functions)
        val tool = decision as AssistantDecision.Call
        assertEquals(functions.single(), tool.function)
        val arguments = JSONObject(tool.argumentsJson)
        assertEquals("Купить молоко", arguments.getString("title"))
        assertEquals(2, arguments.getInt("priority"))
        assertEquals("home", arguments.getJSONArray("labels").getString(0))
    }

    @Test fun `text without a call is a reply and nothing is nothing`() {
        assertEquals(AssistantDecision.Reply("Нет подходящего инструмента."),
            OnDeviceAssistant.decision(" Нет подходящего инструмента. ", emptyList(), functions))
        assertEquals(AssistantDecision.Nothing, OnDeviceAssistant.decision("", emptyList(), functions))
        val unknown = FunctionCall("other", emptyMap(), "call-2")
        assertEquals(AssistantDecision.Nothing, OnDeviceAssistant.decision(null, listOf(unknown), functions))
    }
}
