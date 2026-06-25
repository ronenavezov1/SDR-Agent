package org.example.actions

import org.example.agent.Action
import org.example.agent.AiAgent

/**
 * An [Action] that delegates work to a target [AiAgent].
 *
 * ## Purpose
 * This is the bridge between the **router agent** and every **specialist agent**.
 * When the LLM inside the router decides to call this action, it means:
 * "I know which specialist should handle this — run it now."
 *
 * ## How the routing works end-to-end
 * 1. The router's `AiAgent` receives the user message.
 * 2. Its LLM sees the system prompt listing all available actions (one per specialist).
 * 3. The LLM calls this action with the user's original message.
 * 4. `perform()` suspends and waits for the target agent to finish.
 * 5. The target agent's answer is returned as the tool result, which the LLM
 *    passes straight back to the user (no extra reasoning step needed).
 *
 * ## Why Action and not Tool?
 * Forwarding to a sub-agent is a **side effect** — it causes another agent to
 * run, build its own history, and potentially call external APIs. This fits
 * the CQRS "Command" side (Action), not the read-only "Query" side (Tool).
 *
 * @param targetAgent The specialist agent to invoke.
 * @param name        Function name the router LLM will use (e.g. "askWeatherAgent").
 * @param description Plain-English capability description shown to the router LLM.
 */
class AgentForwardAction(
    private val targetAgent: AiAgent,
    override val name: String,
    override val description: String
) : Action {

    /**
     * Parameter: the user's original question, passed verbatim by the router LLM.
     * The system prompt instructs the LLM never to paraphrase it.
     */
    override val parameters: Map<String, String> = mapOf(
        "question" to "The user's original message, copied verbatim."
    )

    /**
     * Forwards the question to [targetAgent] and returns its answer.
     *
     * This suspends (does not block) while the target agent runs its own
     * ReAct loop — which may itself call tools, external APIs, etc.
     *
     * @param agentId The ID of the router agent (used for logging).
     * @param args    Must contain key "question" with the user's message.
     */
    override suspend fun perform(agentId: String, args: Map<String, Any>): String {
        val question = args["question"]?.toString()?.trim()
            ?: return "Error: 'question' argument is required."

        println("📨 [$agentId] Forwarding via action '$name': \"${question.take(80)}\"")

        return targetAgent.processInput(question)
    }
}