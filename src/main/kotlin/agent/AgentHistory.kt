package org.example.agent

import java.util.UUID

// מייצג פריט אחד במערך הזיכרון (מה שקראת לו rember בשרטוט)
sealed interface AgentHistory {
    val id: String // לזהות ייחודית של כל פעולה (למשל עבור לוגים)
    val timestamp: Long

    // 1. הודעת טקסט רגילה מהמשתמש (או מסוכן אחר)
    data class UserInput(
        val text: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory

    // 2. תגובת טקסט סופית של ה-LLM
    data class AiResponse(
        val text: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory

    // 3. ה-LLM מבקש להפעיל פונקציה (Request)
    data class ToolCallRequest(
        val toolName: String,
        val arguments: Map<String, Any>,
        /** Opaque bytes from thinking models — must be echoed back when replaying history. */
        val thoughtSignature: ByteArray? = null,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory

    // 4. התוצאה של הפונקציה שחזרה ל-LLM (Tool)
    data class ToolExecutionResult(
        val toolName: String,
        val resultData: String,
        val isSuccess: Boolean = true, // חשוב כדי שהמודל ידע אם הכלי נכשל!
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory

    // 5. הודעת מערכת (למשל: דחיסת זיכרון או שגיאה פנימית)
    data class SystemEvent(
        val eventDescription: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory

    /**
     * 6. Memory summary — replaces the entire history after compression.
     *
     * When an agent's history grows too large, [AiAgent.summarizeHistory] asks
     * the LLM to condense all past turns into a single paragraph.
     * The full history is then cleared and replaced with exactly this one entry.
     *
     * Keeping a dedicated type (instead of reusing [AiResponse]) lets every
     * when-expression explicitly handle summaries — e.g. the LLM adapter can
     * present it with a "SUMMARY:" prefix so the model knows it is compressed
     * context, not a live conversation turn.
     *
     * @param text The LLM-generated summary of everything that happened so far.
     */
    data class Summary(
        val text: String,
        override val id: String = UUID.randomUUID().toString(),
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentHistory
}