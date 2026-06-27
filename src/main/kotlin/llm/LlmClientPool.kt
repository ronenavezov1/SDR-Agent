package org.example.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.example.debug.DebugLogger

/**
 * A pool of [LlmClient] instances with exclusive-use semantics and permanent dead-marking.
 *
 * ## Client states
 * | State | Meaning |
 * |---|---|
 * | **Available** | Ready to accept a call |
 * | **InUse** | Exclusively held by one agent coroutine |
 * | **Dead** | Failed after all BaseLlmClient retries — permanently removed from rotation |
 *
 * ## How acquire works
 * A [Semaphore] tracks the number of available slots.  [acquire] calls `semaphore.acquire()`
 * which suspends the coroutine until a slot is free — no polling, no spinning.
 * When a slot is released successfully the semaphore permit is returned and one waiting
 * coroutine is woken.  When a slot dies the permit is NOT returned, so capacity shrinks.
 *
 * ## All-dead unblocking
 * If the last living client dies while coroutines are suspended in [acquire], one "wake"
 * permit is released.  Each woken coroutine detects all-dead, re-releases the permit
 * (chain-signalling the next waiter), and returns null.
 *
 * ## Edge cases
 * - No clients configured — [acquire] returns null immediately.
 * - All clients dead     — [acquire] returns null and chain-unblocks all waiters.
 */
class LlmClientPool(clients: List<LlmClient>) {

    private enum class State { Available, InUse, Dead }

    private inner class Slot(val client: LlmClient) {
        @Volatile var state: State = State.Available
    }

    private val slots     = clients.map { Slot(it) }
    private val mutex     = Mutex()
    private val semaphore = Semaphore(clients.size)   // permits = available count
    @Volatile private var deadCount = 0

    val size: Int get() = slots.size

    /**
     * Acquires an exclusive client, suspending until one is free.
     * Returns null if no clients were configured or all are permanently dead.
     */
    suspend fun acquire(): LlmClient? {
        if (slots.isEmpty() || deadCount == slots.size) return null

        semaphore.acquire()   // suspend until a permit (= available slot) is released

        // Woken by the all-dead chain-signal — propagate to next waiter and give up.
        if (deadCount == slots.size) {
            semaphore.release()
            return null
        }

        return mutex.withLock {
            slots.firstOrNull { it.state == State.Available }
                ?.also { it.state = State.InUse }
                ?.client
        } ?: run {
            // Defensive: permit was available but no slot found (shouldn't happen).
            if (deadCount == slots.size) semaphore.release()
            null
        }
    }

    /**
     * Releases [client] back to the pool.
     * - failed=false: marks Available and returns the semaphore permit (wakes one waiter).
     * - failed=true:  marks Dead permanently; if this was the last client alive, sends
     *                 one "all-dead" permit to unblock any suspended [acquire] callers.
     */
    suspend fun release(client: LlmClient, failed: Boolean) {
        val allDead = mutex.withLock {
            val slot = slots.firstOrNull { it.client === client } ?: return
            if (failed) {
                slot.state = State.Dead
                ++deadCount == slots.size   // true when last client just died
            } else {
                slot.state = State.Available
                false
            }
        }
        if (failed) {
            DebugLogger.llmClientFailure(client.providerName, "Permanently removed from pool after exhausting all retries")
            if (allDead) semaphore.release()   // chain-unblock waiting acquires
        } else {
            semaphore.release()   // return permit — wake one waiting coroutine
        }
    }
}
