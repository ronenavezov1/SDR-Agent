# AI SDR Agent — Autonomous Lead Qualification

### monday.com RevAI Engineering Challenge

---

## The Core Idea

> **"Good architecture is stable, easy to extend, and easy to change policies."**

This system filters thousands of inbound leads automatically. It separates spam from real prospects, qualifies the good ones, and surfaces them to sales reps — ready for a meeting.

If every LLM in the world goes down? The system keeps running. Every lead is saved, and sales reps go back to sorting manually. **The system never stops working.**

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

> This is the heart of the system. Let me walk you through it.

![MVI System Architecture Diagram](MVI%20System%20Architecture%20Diagram.png)

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
1. `spam-detector` — Is this real or spam?
2. `qualification-extractor` — Pull out use_case, team_size, commercial_intent
3. `escalation-detector` — Did they mention pricing, contracts, or competitors?
4. `initial-intent-checker` — Do they want a human? Is there enough info? Should we proceed?
5. `info-sufficiency` — Enough data to make a final decision?
6. `deal-decision` / `outreach-writer` — Qualify, disqualify, or send a follow-up email
7. `farewell-writer` — Final email with a booking link

### Reply Flow:
`qualification-extractor` → `escalation-detector` → `lead-readiness` → `info-sufficiency` → `deal-decision` / `followup-writer` → `farewell-writer`

---

## Agent Map — Each Agent's Purpose

![Layered Architecture: Sub-Agents Capability Map](Layered%20Architecture%3A%20Sub-Agents%20Capability%20Map.png)

![Class Diagram: Sub-Agent Definitions](Class%20Diagram%3A%20Sub-Agent%20Definitions.png)

| Agent | What it decides | Why it's separate |
|-------|----------------|-------------------|
| `spam-detector` | `REAL` / `SPAM` | Saves tokens — no point processing junk |
| `qualification-extractor` | Extracts qualification fields | Runs early — saves data even if the pipeline stops |
| `escalation-detector` | `SAFE` / `ESCALATED` | Hard rule — pricing always goes to a human |
| `deal-decision` | Qualify / Disqualify | **The only agent** that can create a booking link |
| `email-reviewer` | `APPROVED` / `REJECTED` | Quality gate — catches bad or generic emails |

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

