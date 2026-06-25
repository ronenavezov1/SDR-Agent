package org.example.llm

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.FunctionResponse
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.google.genai.types.Schema
import com.google.genai.types.Type
import com.google.genai.types.FunctionCall as GenAiFunctionCall
import com.google.genai.types.Tool as GenAiTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.agent.AgentHistory
import org.example.agent.AgentCapability

class GeminiLlmClient(apiKey: String, modelName: String = "gemini-2.5-flash") :
    BaseLlmClient(providerName = "Google Gemini", apiKey, modelName) {

    private val client = Client.builder().apiKey(apiKey).build()

    override fun buildRequestPayload(
        system: String,
        history: List<AgentHistory>,
        capabilities: List<AgentCapability>
    ): Any = GeminiPayload(system, history, capabilities)

    override suspend fun executeNetworkCall(agentId: String, payload: Any): LlmResponse {
        val req = payload as GeminiPayload
        return withContext(Dispatchers.IO) {
            val contents = toContents(req.history)

            println("\n+===== [$agentId] Gemini Request (${contents.size} content items) =====")
            contents.forEachIndexed { i, c ->
                val role = c.role().orElse("?")
                val parts = c.parts().orElse(emptyList()).joinToString(" | ") { p ->
                    when {
                        p.text().isPresent ->
                            "TEXT(\"${p.text().get().take(60).replace('\n', ' ')}\")"
                        p.functionCall().isPresent ->
                            "FUNC_CALL(${p.functionCall().get().name().orElse("?")}, args=${p.functionCall().get().args().orElse(emptyMap<String, Any>())})"
                        p.functionResponse().isPresent ->
                            "FUNC_RESP(${p.functionResponse().get().name().orElse("?")})"
                        else -> "UNKNOWN_PART"
                    }
                }
                println("|  [$i] role=$role -> $parts")
            }
            println("+==============================================================")

            val config = buildConfig(req.system, req.capabilities)
            val response = client.models.generateContent(modelName, contents, config)

            val funcCalls = (response.functionCalls() ?: emptyList<GenAiFunctionCall>()).map { fc ->
                @Suppress("UNCHECKED_CAST")
                (FunctionCall(
                    functionName = fc.name().orElse(""),
                    arguments = fc.args().orElse(emptyMap<String, Any>()) as Map<String, Any>
                ))
            }

            val textReply = if (funcCalls.isEmpty()) response.text()?.takeIf { it.isNotBlank() } else null

            // LOG: show what Gemini returned
            println("+--- [$agentId] Gemini Response --------------------------------")
            if (funcCalls.isNotEmpty()) {
                funcCalls.forEach { fc ->
                    println("|  FUNC_CALL -> ${fc.functionName}(${fc.arguments})")
                }
            } else {
                println("|  TEXT -> \"${textReply?.take(120)?.replace('\n', ' ')}\"")
            }
            println("+--------------------------------------------------------------\n")

            LlmResponse(textReply = textReply, functionCalls = funcCalls)
        }
    }

    // KEY FIX: Consecutive ToolCallRequests MUST be merged into a single model
    // Content with multiple FunctionCall Parts (Gemini forbids two consecutive
    // model turns). Same for consecutive ToolExecutionResults -> single user Content.
    private fun toContents(history: List<AgentHistory>): List<Content> {
        val result = mutableListOf<Content>()
        var i = 0
        while (i < history.size) {
            when (val action = history[i]) {

                is AgentHistory.UserInput -> {
                    result += Content.builder()
                        .role("user")
                        .parts(listOf(Part.builder().text(action.text).build()))
                        .build()
                    i++
                }

                is AgentHistory.AiResponse -> {
                    result += Content.builder()
                        .role("model")
                        .parts(listOf(Part.builder().text(action.text).build()))
                        .build()
                    i++
                }

                // Merge ALL consecutive ToolCallRequests -> ONE model Content
                is AgentHistory.ToolCallRequest -> {
                    val parts = mutableListOf<Part>()
                    while (i < history.size && history[i] is AgentHistory.ToolCallRequest) {
                        val req = history[i] as AgentHistory.ToolCallRequest
                        val fc = GenAiFunctionCall.builder()
                            .name(req.toolName)
                            .args(req.arguments)
                            .build()
                        parts += Part.builder().functionCall(fc).build()
                        i++
                    }
                    result += Content.builder().role("model").parts(parts).build()
                }

                // Merge ALL consecutive ToolExecutionResults -> ONE user Content
                is AgentHistory.ToolExecutionResult -> {
                    val parts = mutableListOf<Part>()
                    while (i < history.size && history[i] is AgentHistory.ToolExecutionResult) {
                        val res = history[i] as AgentHistory.ToolExecutionResult
                        val fr = FunctionResponse.builder()
                            .name(res.toolName)
                            .response(mapOf<String, Any>("output" to res.resultData))
                            .build()
                        parts += Part.builder().functionResponse(fr).build()
                        i++
                    }
                    result += Content.builder().role("user").parts(parts).build()
                }

                // SystemEvent - skip, never sent to the model
                is AgentHistory.SystemEvent -> i++

                // Summary - sent as a user-role "context" turn so the model
                // knows it is compressed prior context, not a live question.
                is AgentHistory.Summary -> {
                    result += Content.builder()
                        .role("user")
                        .parts(listOf(Part.builder()
                            .text("CONVERSATION SUMMARY (compressed context):\n${action.text}")
                            .build()))
                        .build()
                    i++
                }
            }
        }
        return result
    }

    private fun buildConfig(systemPrompt: String, capabilities: List<AgentCapability>): GenerateContentConfig {
        val sysContent = Content.builder()
            .parts(listOf(Part.builder().text(systemPrompt).build()))
            .build()

        val configBuilder = GenerateContentConfig.builder()
            .systemInstruction(sysContent)

        if (capabilities.isNotEmpty()) {
            val funcDecls = capabilities.map { cap ->
                val propsSchema: Map<String, Schema> = cap.parameters.mapValues { (_, desc) ->
                    Schema.builder()
                        .type(Type("STRING"))
                        .description(desc)
                        .build()
                }
                val paramsSchema = Schema.builder()
                    .type(Type("OBJECT"))
                    .properties(propsSchema)
                    .required(cap.parameters.keys.toList())
                    .build()

                FunctionDeclaration.builder()
                    .name(cap.name)
                    .description(cap.description)
                    .parameters(paramsSchema)
                    .build()
            }
            configBuilder.tools(listOf(
                GenAiTool.builder().functionDeclarations(funcDecls).build()
            ))
        }

        return configBuilder.build()
    }

    private data class GeminiPayload(
        val system: String,
        val history: List<AgentHistory>,
        val capabilities: List<AgentCapability>
    )
}
