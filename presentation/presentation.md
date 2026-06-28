# AI SDR Agent — Autonomous Lead Qualification

### monday.com RevAI Engineering Challenge

---

## The Problem

Every day, sales teams receive hundreds of inbound messages.

Most of them are spam. Some are real leads — but buried inside unclear, incomplete emails that say things like *"interested in your product, let me know."*

A human SDR has to open every single one, read it, decide if it's worth their time, write a reply, wait for a response, and repeat — for every lead, every day.

That's slow. It's expensive. And good leads fall through the cracks while the team is busy with the noise.

**The question is: can we automate this — without losing control over who actually gets a booking link?**

---

## The Core Idea

This system is built around two principles.

**Principle 1 — Good architecture.**
> *"Stable, easy to extend, and easy to change policies."*

The business rules live in Kotlin code. The LLM is just a tool — like a database or an email service. You can swap it, replace it, or remove it. The core logic doesn't move.

**Principle 2 — Never depend on the LLM to stay alive.**

> *What if every LLM in the world goes down tonight?*

The system doesn't crash. It doesn't lose leads. It sends a hardcoded farewell email with a booking link — no AI needed — and puts the lead in a manual review queue. Sales reps take over. **Every lead is saved. The system never stops.**

This is called **Graceful Failure** — and it's built in from day one, not added later.

---

## Three Core Guarantees

### 1. Zero Lead Leakage
Every lead reaches a terminal state. Nothing ever disappears.

### 2. Hard Guardrails
Business rules live in Kotlin code, not in prompts. The LLM cannot override them.

### 3. Graceful Failure
If all LLM providers fail, the system sends a hardcoded farewell email with a booking link — no LLM needed. Sales reps get a list to review manually. **No lead is ever lost.**

---

## System Architecture — MVI

> Let's start with the macro view — to see how the system works technically. Then we'll dive into the micro view — to look at the agent's decisions and how its flow works.

![MVI System Architecture Diagram](MVI%20System%20Architecture%20Diagram.png)

When a lead arrives, the **Repository** spawns an **Orchestrator** and assigns it an LLM client. The Orchestrator runs asynchronously — with a lock on the lead's email address, so two Orchestrators can never process the same lead at the same time. Each LLM client is dedicated to one Orchestrator at a time.

Inside the Orchestrator, **sub-agents run synchronously** — one at a time, in order. Each sub-agent sends a request to the LLM — along with its tools and conversation history. The LLM may respond with a tool call or an action. The agent executes it, returns the result to the LLM, and the loop continues until the LLM gives a final answer. Only then does the sub-agent return, and the next one begins.

![Class Diagram: MVI Architecture](Class%20Diagram%3A%20MVI%20Architecture.png)

The key idea: **business logic knows nothing about the outside world.**

| Layer | Role | What lives there |
|-------|------|-----------------|
| **View** | Pure I/O — today CLI, tomorrow REST or Kafka | `SdrConsoleView` |
| **Intent (ViewModel)** | Translates raw input into typed calls | `SdrViewModel` |
| **Model (Repository)** | Owns all state — single source of truth | `SdrRepository` |
| **Orchestrator** | Stateless — runs 12 agents, holds no data | `SdrOrchestrator` |

**Why MVI for an AI system?**
In most LLM integrations, the model writes directly to the database — impossible to test or audit. Here it's different: **the LLM produces text, Kotlin decides what to do with it.** Every state change is a defined, logged action.

### Dual-Thread Design

- **Thread 1 (Input):** Reads commands, dispatches them as fire-and-forget, and returns to the prompt immediately.
- **Thread 2 (Results):** Listens on a `SharedFlow` and prints results as they arrive.

The user keeps working while the agent processes in the background — exactly how it would work in production with webhooks.

---

## Resilience — Three Layers of Defence

Now that we see the architecture, the next question is: **what happens when the LLM fails?**

![State Diagram: LLM Client Pool](State%20Diagram%3A%20LLM%20Client%20Pool.png)

![Class Diagram: LLM Client Pool Subsystem](Class%20Diagram%3A%20LLM%20Client%20Pool%20Subsystem.png)

### Layer 1: Retry with Exponential Backoff
Each call gets up to 5 attempts with exponential backoff — delays of 2.5s → 5s → 10s → 10s (capped). Only known permanent errors (400, 401, 403) fail immediately. **Everything else is retried** — including rate limits, network errors, and any unexpected SDK exceptions.

### Layer 2: LLM Client Pool with Tiered Models
Semaphore-based pool. Each slot holds **two models under the same API key** — `gemini-3.5-flash` (FAST tier) and `gemini-2.5-pro` (SMART tier). Agents declare their tier; the client routes to the right model automatically. A dead client is permanently marked and the next one takes over.

### Layer 3: Full Fallback
All clients dead? The system creates a fallback booking link, sends a hardcoded email, and marks the lead as `ApprovedLlmFailed`. No LLM needed.

**Important:** `LeadReceived` is logged *before* the pool is touched — so no lead can vanish due to an LLM failure.

**No Deadlock by Design:** Lock ordering is fixed — the orchestrator always locks the **lead** first, then acquires an **LLM client** from the pool. Since every coroutine follows the same order, circular wait is impossible and deadlocks cannot occur.

**Graceful Failure:** If all LLM clients crash, waiting orchestrators don't sleep forever. The pool uses chain-signalling: the last dying client releases one semaphore permit, which wakes a waiting coroutine; that coroutine detects all-dead, re-releases the permit to wake the next waiter, and so on. Every orchestrator wakes up, receives `null`, and falls through to Layer 3 (fallback email + `ApprovedLlmFailed`) — a clean, graceful failure.

---

## How an Agent Works — The ReAct Loop

Before we dive into the business logic, let me explain **how each agent actually works** under the hood.

![Activity Diagram: Sub-Agent Execution Flow](Activity%20Diagram%3A%20Sub-Agent%20Execution%20Flow.png)


### Synchronous execution

Each agent runs **synchronously** — it's called, it does its work, and it returns a result. There's no background processing inside the agent itself.

The flow is simple:

1. The agent receives input and sends it to the LLM — along with a system prompt, its conversation history, and a list of available tools.
2. If the LLM returns **function calls** — the agent executes them, adds the results to its history, and calls the LLM again.
3. If the LLM returns a **text reply** — that's the final answer. The agent returns it and it's done.

### History management

The agent keeps an internal conversation history — every input, every LLM response, every tool result. This is how the LLM maintains context across multiple tool calls within a single run.

But this history can grow. If it reaches **80% of the maximum window size**, the agent asks the LLM to **summarize** the entire conversation into a single compact entry. The old history is replaced with that summary, and the loop continues with a much smaller context. This prevents token overflow without losing important information.

After the agent finishes — whether it succeeded or failed — `finally { clearHistory() }` runs. The history is always wiped clean. No leftovers, no memory leaks.

### Three safeguards against infinite execution

- **maxDepth** — each agent has a maximum recursion depth. If the LLM keeps calling tools without giving a final answer, the loop stops.
- **timeout** — total execution time is capped. If the agent takes too long, it's cut off.
- **History window** — the auto-summarization described above prevents the context from growing unbounded.

Every one of the 12 agents uses this exact same mechanism.

---

### The ReAct Loop: What the LLM Sees

**1. First Call (Optimized):**
The agent sends the LLM a prompt that is often "pre-loaded" with context to save a turn:
- **System Prompt:** "You are a qualification extractor..."
- **Available Tools:** `getLeadState`, `updateQualification`
- **Pre-loaded Context:** "Here is the current state of the lead: { team_size: 50, ... }"
- **User Input:** "Your task is to extract new information from this message: '...'"

This **proactive context injection** saves the LLM from having to call `getLeadState` itself, saving one full loop cycle.

**2. N-th Call (With History):**
If the LLM needs more information, its next prompt includes the full history of its own thought process:
- **System Prompt** (same as before)
- **Available Tools** (same as before)
- **Full History:**
    - User Input: "..."
    - Agent Thought: "I need to check the lead's qualification status."
    - Tool Call: `checkQualification()`
    - Tool Result: "{ use_case: 'known', team_size: 'known', commercial_intent: 'unknown' }"
- **New Request:** "You have 2 steps remaining. What is your next thought or final answer?"

Notice the two key details: the full history provides memory, and the "steps remaining" hint encourages the LLM to converge on a final answer instead of looping indefinitely. The `maxDepth` limit itself is still enforced in Kotlin as a hard guardrail.

---

### History vs. Events: Two Levels of Memory

The system uses two different kinds of "memory" for different purposes.

**Agent `History`:**
- **What it is:** A temporary, in-memory log of a single agent's thought process *during one request*.
- **Scope:** Private to one `AiAgent` instance.
- **Lifecycle:** Created when the agent starts, **cleared** completely when the agent finishes.
- **Purpose:** Allows the agent to "reason" step-by-step within a single ReAct loop (e.g., "I called this tool, got this result, so my next step is..."). It's the agent's short-term memory.

**Lead `Events`:**
- **What it is:** A permanent, append-only log of meaningful business outcomes for a lead.
- **Scope:** Attached to the `Lead` object, persisted in the database.
- **Lifecycle:** Lives forever with the lead.
- **Purpose:** Provides the long-term, auditable source of truth for a lead's entire journey. Examples: `LeadReceived`, `EmailSent`, `HumanEscalationTriggered`, `BookingLinkCreated`. It's the system's long-term memory.

---

## CQRS — Tools vs Actions

Each agent has access to **capabilities** — and they come in two types:

![CQRS Architecture Diagram: Sub-Agent Capabilities](CQRS%20Architecture%20Diagram%3A%20Sub-Agent%20Capabilities.png)

| Type | What it does | Examples |
|------|-------------|----------|
| **Tool** 🔍 (Query) | Reads data, no side effects | `getLeadState`, `checkQualification` |
| **Action** ⚡ (Command) | Performs a side effect | `createBookingLink`, `disqualifyLead`, `sendEmail` |

This separation means **an agent can only do what it's been assigned.** Only `deal-decision` has access to `createBookingLink` — that's enforced in Kotlin, not in a prompt.

---

## Statelessness — Why Everything Is Disposable

Now here's a key design decision: **the agent we just described is completely stateless.** It's created, it runs, it returns a result, and it's garbage-collected. The same goes for the orchestrator that calls it — and every one of the 12 agents inside.

Why does this matter?

| Risk | Typical design | This design |
|------|---------------|-------------|
| Cross-lead contamination | Data from Lead A leaks into Lead B | Impossible — fresh instance every time |
| Memory leaks | History accumulates over time | Cleared in `finally{}` after every call |
| Concurrency corruption | Two replies corrupt shared state | Per-lead `Mutex` serialises access |

Every agent starts with a **full context snapshot** — status, qualification fields, email thread, and event history. It's never working blind, but it also never carries baggage from previous requests.

This is what makes the system safe to scale: you can run 100 leads in parallel, and they'll never interfere with each other.

---

## Orchestration — The Conductor

Now we move from the technical foundation to the business logic. The orchestrator is the conductor — it decides **which agents run, in what order, and what to do with their results.**

![Activity Diagram: Orchestration](Activity%20Diagram%3A%20Orchestration.png)

![Layered Architecture: Sub-Agents Capability Map](Layered%20Architecture%3A%20Sub-Agents%20Capability%20Map.png)

![Class Diagram: Orchestration](Class%20Diagram%3A%20Orchestration.png)

### `OrchestratorContext` — least-privilege access:
- Agents never talk to the Repository directly.
- They get a scoped interface: `getLead()`, `saveLead()`, `sendEmail()`, `createBookingLink()`.
- All I/O flows through the context — one place to control everything.

The orchestrator itself is stateless — just like every agent inside it. It receives a context, runs the pipeline, and returns. No data is held between requests.

---

## Multi-Agent Pipeline — 12 Agents

> **One agent, one decision, one responsibility.** No agent makes a decision outside its scope.

![Activity Diagram: Lead Processing Pipelines](Activity%20Diagram%3A%20Lead%20Processing%20Pipelines.png)

### New Lead Flow:
1. `spam-detector` — Is this real or spam? → SPAM → disqualify and exit
2. `qualification-extractor` — Pull out use_case, team_size, commercial_intent from the message
3. `escalation-detector` — Did they mention pricing, contracts, or competitors? → ESCALATED → escalate and exit
4. `initial-intent-checker` — Classify the lead's first-message intent:
   - `CLIENT_WANTS_HUMAN` → `farewell-writer` + booking link → exit
   - `IRRELEVANT` → disqualify → exit
   - `DECIDE_NOW` → `deal-decision` → (if qualified) `farewell-writer` → exit
   - `PROCEED` → continue ↓
5. `info-sufficiency` — Enough data to make a final decision?
   - `DISQUALIFY_NOW` / `DECIDE_NOW` → `deal-decision` → (if qualified) `farewell-writer` → exit
   - `NEEDS_MORE_INFO` → continue ↓
6. `outreach-writer` → `email-reviewer` → (if rejected: retry → `email-sanity-checker`) → send outreach email

### Reply Flow:
1. `qualification-extractor` — Extract new data from the reply
2. `escalation-detector` — Escalation triggers? → ESCALATED → escalate and exit
3. `lead-readiness` — Analyse lead behaviour across the full conversation:
   - `CLIENT_WANTS_HUMAN` → `farewell-writer` + booking link → exit
   - `DECIDE_NOW` → `deal-decision` → (if qualified) `farewell-writer` → exit
   - `GATHER_MORE` → continue ↓
4. `info-sufficiency` — Enough data to decide?
   - `DISQUALIFY_NOW` / `DECIDE_NOW` → `deal-decision` → (if qualified) `farewell-writer` → exit
   - `NEEDS_MORE_INFO` → continue ↓
5. `followup-writer` → `email-reviewer` → (if rejected: retry → `email-sanity-checker`) → send follow-up email

### Resolve Escalation Flow:
1. `info-sufficiency` — Interpret the human's response + check data state:
   - `DECIDE_NOW` + all fields present → `deal-decision` → (if qualified) `farewell-writer` → exit
   - `DECIDE_NOW` + fields missing → `ApprovedSalesTeamAsk` + `farewell-writer` → exit (no `deal-decision`)
   - `NEEDS_MORE_INFO` → continue ↓
2. `followup-writer` → `email-reviewer` → (if rejected: `email-sanity-checker`) → send follow-up relaying the human's answer

---

## Agent Map — Each Agent's Purpose

![Class Diagram: Sub-Agent Definitions](Class%20Diagram%3A%20Sub-Agent%20Definitions.png)

| Agent | What it decides | Why it's separate |
|-------|----------------|-------------------|
| `spam-detector` | `REAL` / `SPAM` | Saves tokens — no point processing junk |
| `qualification-extractor` | Extracts qualification fields | Runs early — saves data even if the pipeline stops |
| `escalation-detector` | `SAFE` / `ESCALATED` | Hard rule — pricing always goes to a human |
| `initial-intent-checker` | `PROCEED` / `IRRELEVANT` / `DECIDE_NOW` / `CLIENT_WANTS_HUMAN` | Fast filter on the first message — avoids wasting follow-ups on irrelevant or human-bound leads |
| `lead-readiness` | `GATHER_MORE` / `DECIDE_NOW` / `CLIENT_WANTS_HUMAN` | Analyses lead **behaviour** over the full conversation — detects stalled or hostile leads |
| `info-sufficiency` | `NEEDS_MORE_INFO` / `DECIDE_NOW` / `DISQUALIFY_NOW` | The only agent that checks **data vs. business rules** — catches small teams early, avoids unnecessary follow-ups |
| `deal-decision` | Qualify / Disqualify | **The only agent** that can create a booking link |
| `outreach-writer` | Writes the first outreach email | Personalised opening — needs SMART model for quality |
| `followup-writer` | Writes follow-up emails | Asks exactly one missing-field question per email |
| `farewell-writer` | Writes the closing email with booking link | Last touch — has a hardcoded fallback if the LLM fails |
| `email-reviewer` | `APPROVED` / `REJECTED` | Quality gate — catches bad or generic emails |
| `email-sanity-checker` | `SEND` / `FAIL` | Last-resort safety net — ensures the email won't embarrass the company |

### Why this order matters:
- `qualification-extractor` runs before escalation — so `"1,000 engineers, need a discount"` saves `teamSize=1000` even if escalated immediately.
- `spam-detector` runs first — no tokens wasted on junk leads.

---

## Guardrails — Safety by Design

### Hard rules in Kotlin, not prompts:
- **No duplicate outreach** — `if (lead.outreachSent) return`
- **Follow-up cap** — after 3 emails, a decision must be made
- **Booking link requires full qualification:**
```kotlin
val hasAllFields = lead.useCase != null &&
                   lead.teamSize != null &&
                   lead.commercialIntent != null
```
- **Pricing = immediate escalation** — no prompt can override this
- **Farewell email always sent** — even if the LLM fails (hardcoded template)

### No infinite loops — ever:
- **Email writer/reviewer cycle** — capped at `maxEmailDraftRetries`. If the reviewer keeps rejecting, the sanity checker gets the last word, and if that fails too — Graceful Failure. The loop cannot run forever.
- **Follow-up cap** — after `maxFollowUps` emails with no decision, the system forces `DECIDE_NOW` regardless of missing data. The pipeline always terminates.
- **Per-agent max depth** — each sub-agent has a `maxDepth` limit. If the LLM keeps calling tools without giving a final answer, the loop is cut off.
- **Per-agent timeout** — total execution time per agent is capped. If the LLM hangs, the agent is cancelled.
- **LLM retry cap** — each LLM call gets a fixed number of retries with exponential backoff. After that, the client is marked dead and the next one takes over.

### Sales rep override — only through the escalation pipeline:
`ApprovedSalesTeamAsk` can only be triggered from inside `resolveEscalation()` — the function that processes a human's explicit response to an open escalation. There is no other code path that sets this status. The LLM cannot reach it, a lead reply cannot reach it. Only a sales rep's deliberate response to an escalation ticket unlocks it.

### Five terminal states — four different booking-link URLs:

| Status | When | Business routing |
|--------|------|-----------------|
| `Disqualified` | Criteria not met | No booking link — lead is closed |
| `Qualified` | All 3 fields confirmed | High-priority AE queue |
| `ApprovedClientAsk` | Lead requested a human | Immediate callback queue |
| `ApprovedSalesTeamAsk` | Sales rep closed escalation | SDR review queue |
| `ApprovedLlmFailed` | All LLMs failed | Manual intervention queue |

---

## Observability — Full Transparency

Every significant action produces a structured event attached to the lead's history:

```
LeadReceived              Initial inbound message
EmailSent                 Subject + full body of every outgoing email
ReplyReceived             Full text of every incoming reply
QualificationUpdated      Which fields changed and why
LeadQualified             All three qualification fields confirmed
LeadDisqualified          Disqualification reason
BookingLinkCreated        URL + reason (AgentQualified / ClientRequestedHuman / SalesTeamHandoff / LlmFailed)
HumanEscalationTriggered  Reason + exact quote from the lead
HumanEscalationResolved   Human response that closed the escalation
PolicyBlocked             Which guardrail fired and why (e.g. NO_DUPLICATE_OUTREACH)
AgentDecision             Decision token + reasoning from a sub-agent
ProcessingFailed          Error reason when all LLM clients fail
```

**Debug mode:** `export SDR_DEBUG=true` — prints full input, conversation history, and output for every agent.

---

## Scaling to Production

The architecture is built so that scaling means **swapping components, not rewriting.**

| Component | Development | Production |
|-----------|-------------|------------|
| Lead storage | `ConcurrentHashMap` | PostgreSQL + row-level locks |
| Concurrency | Kotlin `Mutex` per lead | DB advisory locks |
| Email sending | `MockEmailRepository` | SendGrid / SES |
| LLM Clients | Single `GeminiLlmClient` | Pool: Gemini + OpenAI + Anthropic |
| Trigger source | CLI | Kafka consumer / HTTP webhook |

**The pipeline — all 12 agents, all 6 actions, all tools — requires zero changes.**
`OrchestratorContext` is the isolation boundary: swap the implementation, and business logic stays untouched.

---

## What I Would Improve Next

### High Priority:
1. **Product context in prompts** — email writers don't yet know the actual product
2. **Idempotency on email sending** — prevent duplicates on retry
3. **Webhook receiver** — make the agent truly event-driven

### Medium Priority:
4. **Tiered LLM models** — ✅ Implemented: FAST tier (`gemini-3.5-flash`) for binary decisions, SMART tier (`gemini-2.5-pro`) for reasoning. Both models share a single API key per pool slot. Next step: cross-provider tiering (different vendors per tier)
5. **Structured LLM output** — JSON Schema instead of free-text
6. **Background no-reply monitor** — a background process that wakes up when a lead doesn't reply, analyses why, and takes corrective action

---

## Summary — Why This Design Works

| Principle | How it's reflected |
|-----------|-------------------|
| **Separation of concerns** | 12 agents, each with one job |
| **State integrity** | Single source of truth in the Repository, append-only events |
| **LLM as infrastructure** | Text in, Kotlin decides what to do |
| **Fail-safe** | 4 defence layers, LLM-free fallback, zero leakage |
| **Testability** | Each layer tested independently, agents are swappable |
| **Scalability** | Swap components, don't rewrite |

---

## Questions?

> The code, diagrams, and full documentation are all in the README.