package org.example.agent

/**
 * Represents the operational status of an [AiAgent].
 *
 * Sealed interface — exhaustive when-expressions are always possible.
 * The agent owns and transitions between these states internally;
 * external code may only READ the current status via [AiAgent.status].
 */
sealed interface AgentStatus {

    /** The agent has finished its last task and is ready for new input. */
    data object Idle : AgentStatus

    /** The agent is waiting for the LLM to respond. */
    data object Thinking : AgentStatus

    /**
     * The agent is actively running one or more tools/actions.
     * @param toolName Comma-separated names of the capabilities being executed.
     */
    data class Working(val toolName: String) : AgentStatus

    /**
     * The agent encountered an unrecoverable error and stopped.
     * @param reason Human-readable explanation of what went wrong.
     */
    data class Error(val reason: String) : AgentStatus
}
