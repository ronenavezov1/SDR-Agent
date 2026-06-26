# AI SDR Agent — monday.com RevAI Engineering Challenge

> An autonomous, multi-agent Sales Development Representative that qualifies or disqualifies inbound leads through personalised email conversations.

**Core guarantees:** zero lead leakage · deterministic state mutations · hard Kotlin guardrails · graceful LLM failure handling

---

## Table of Contents

1. [Architecture: MVI & Unidirectional Data Flow](#1-architecture-mvi--unidirectional-data-flow)
2. [Statelessness & Per-Request Lifecycle](#2-statelessness--per-request-lifecycle)
3. [Multi-Agent Pipeline](#3-multi-agent-pipeline)
4. [Guardrails, Policies & Human-in-the-Loop](#4-guardrails-policies--human-in-the-loop)
5. [Resilience & Email Quality](#5-resilience--email-quality)
6. [Observability](#6-observability)
7. [Open Design Question](#7-open-design-question)
8. [Scaling to Thousands of Leads](#8-scaling-to-thousands-of-leads)
9. [What I Would Improve Next](#9-what-i-would-improve-next)
10. [Getting Started](#10-getting-started)

---

## 1. Architecture: MVI & Unidirectional Data Flow

The system enforces the **MVI (Model-View-Intent)** pattern. All state mutations are unidirectional, named, and logged. The LLM can only call declared `Action` functions — it cannot invent a new state transition.

```
VIEW         SdrConsoleView  (dual-thread)
             Thread 1: reads stdin, dispatches intents (fire-and-forget)
             Thread 2: collects SharedFlow, prints results with separator
               | typed intent
               v
INTENT       SdrViewModel
             Translates inputs into submit*() calls. No business logic.
               | submit*(lead / reply / resolve)
               v
MODEL        SdrRepository  <--  Single Source of Truth
             - Owns all Lead objects; the only layer that mutates them
             - Per-lead Mutex: one pipeline per lead at a time
             - Tries LLM clients in sequence; handles total failure
             - Emits ProcessingResult via SharedFlow (non-blocking)
               | OrchestratorContext  (least-privilege scoped interface)
               v
ORCHESTRATOR SdrOrchestrator  (stateless, ephemeral)
             - Fresh instance per request; GC'd when done
             - Coordinates 12 specialised agents
             - Reads state via Tools, writes state via Actions
```

**Why MVI for an AI system:** A conventional integration lets the LLM write to the database however it wants, creating bugs that are impossible to unit-test. Here, the LLM produces text; Kotlin decides what changes. Every mutation is a named, logged event. `UpdateQualificationAction` writes one specific field. Nothing else.

---

## 2. Statelessness & Per-Request Lifecycle

Both `SdrOrchestrator` and `AiAgent` are completely stateless. This is the system’s primary correctness guarantee.

```
SdrRepository.submitReply("lead@x.com", "message")
    |-- acquires per-lead Mutex            (blocks duplicate concurrent calls)
    |-- createContext(leadEmail, client)   (scoped to this lead + this LLM key)
    |-- new SdrOrchestrator(ctx)            <- allocated here
    |       |-- new AiAgent(config) x 11    <- fresh history each
    |               |-- processInput()     -> runs pipeline
    |               |-- clearHistory()     -> always runs in finally{}
    |-- [Orchestrator + all Agents are garbage collected]
```

| Risk | Traditional agent | This design |
|---|---|---|
| Cross-lead context pollution | Lead A’s data bleeds into Lead B | Impossible — new instance per request |
| History bloat | Old turns inflate token cost indefinitely | History cleared in `finally{}` |
| Concurrency corruption | Two replies corrupt state | Per-lead Mutex serialises access |

Every agent receives the **full lead context** in its initial prompt (`lead.toContextString()` — status, email thread, events, qualification fields). Agents never start blind.

---

## 3. Multi-Agent Pipeline

12 specialised agents. One agent, one decision, one action.

```
NEW LEAD:
  spam-detector -> qualification-extractor -> escalation-detector
  -> initial-intent-checker -> info-sufficiency -> deal-decision | email-writer chain

REPLY:
  qualification-extractor -> escalation-detector
  -> lead-readiness -> info-sufficiency -> deal-decision | email-writer chain

EMAIL WRITER CHAIN (shared):
  outreach-writer / followup-writer
    -> parseDraft()          (format gate)
    -> email-reviewer        (strict: no placeholders, single question, no pricing)
        APPROVED -> send
        REJECTED -> rewrite  (up to maxEmailDraftRetries)
    -> email-sanity-checker  (lenient: non-empty, coherent, no placeholders)
        SEND -> send    FAIL -> LlmSendException -> tryWithAllClients

BOOKING LINK ISSUED (always, bypasses follow-up cap):
  farewell-writer -> parseDraft() -> send  (fallback template if LLM fails)
```

**Key ordering decisions:**

- `qualification-extractor` runs **before** escalation — *"1,000 engineers, need a discount"* saves `teamSize=1000` even though it triggers escalation. When the human resolves it, partial data already exists.
- `spam-detector` runs **first** on new leads — no tokens wasted on junk, event log stays clean. Defaults to `REAL` when uncertain.
- `initial-intent-checker` (new leads) vs `lead-readiness` (replies) — fundamentally different questions. Running `lead-readiness` on a first message has no conversation history to judge.

### Agent Configuration

| Agent | Pipeline | Role | Max Depth | Timeout |
|---|---|---|---|---|
| `spam-detector` | New lead | Genuine vs. junk | 1 | 15 s |
| `initial-intent-checker` | New lead | First-message intent | 1 | 15 s |
| `qualification-extractor` | Both | Field extraction | 3 | 30 s |
| `escalation-detector` | Both | Pricing / legal triggers | 3 | 30 s |
| `lead-readiness` | Reply | Behaviour assessment | 3 | 20 s |
| `info-sufficiency` | Both | Completeness vs. budget | 4 | 30 s |
| `deal-decision` | Both | Final qualify/disqualify | 5 | 60 s |
| `outreach-writer` | New lead | First email draft | 4 | 60 s |
| `followup-writer` | Reply | Follow-up email draft | 3 | 45 s |
| `email-reviewer` | Both | Strict quality gate | 2 | 20 s |
| `email-sanity-checker` | Both | Last-resort safety gate | 2 | 15 s |
| `farewell-writer` | Both | Personalised farewell + booking link | 3 | 30 s |

### Terminal States

A lead reaches exactly **one of four terminal states**, each with a distinct booking URL so downstream CRM systems route without ambiguity.

| Status | Booking URL | Meaning |
|---|---|---|
| `Qualified` | `/qualified/{id}` | All 3 fields confirmed, agent decision |
| `ApprovedClientAsk` | `/client-ask/{id}` | Lead requested a human; immediately honoured |
| `ApprovedSalesTeamAsk` | `/sales-team/{id}` | Sales rep closed escalation, data incomplete |
| `ApprovedLlmFailed` | `/fallback/{id}` | All LLM clients failed; hardcoded farewell sent; human handles manually |

Each `BookingLinkCreated` event records the **reason** from the `BookingLinkReason` enum, so it is always clear why a lead received a link.

---

## 4. Guardrails, Policies & Human-in-the-Loop

Business policies live in the **execution layer** — enforced in Kotlin before any agent runs. The LLM has zero discretion over these rules.

### Hard Policies

| Policy | Enforcement |
|---|---|
| No duplicate outreach | `if (lead.outreachSent) return` in `SendOutreachEmailAction` |
| Hard follow-up cap | `remainingFollowUps == 0` forces `DECIDE_NOW` — lead never silently abandoned |
| Booking links require qualification | `CreateBookingLinkAction` only registered on `deal-decision`; no other agent can call it |
| Pricing forces escalation | `Escalated` status blocks pipeline on every entry; no prompt can bypass |
| Farewell email on booking | Whenever a booking link is issued (`Qualified`, `ApprovedClientAsk`, `ApprovedSalesTeamAsk`), `farewell-writer` sends a personalised closing email with the link — always, even if follow-up budget is exhausted; falls back to a hardcoded template if LLM fails |

**Iron Rule (Kotlin, not prompt):**
```kotlin
// Qualified is unreachable unless all three fields are non-null.
// No prompt engineering can override this.
val hasAllFields = lead.useCase != null &&
                   lead.teamSize != null &&
                   lead.commercialIntent != null
```

### Escalation Triggers

| Trigger | Agent | Outcome |
|---|---|---|
| Pricing / discounts / budget | `escalation-detector` | `Escalated` — pipeline blocked |
| Lead asks for a human | `lead-readiness` → `CLIENT_WANTS_HUMAN` | `ApprovedClientAsk` — terminal |
| Anger / frustration | `lead-readiness` → `CLIENT_WANTS_HUMAN` | Same |

### Human Intent Detection on Resolution

When a human resolves an escalation, `infoSufficiency` interprets their **intent** — not just their words:

```
Human: "10% discount approved"
  -> NEEDS_MORE_INFO  (answered a question, not closing the lead)
  -> followup-writer relays the answer + asks the next missing qualification field

Human: "Forward this lead to sales"
  -> DECIDE_NOW + data incomplete  -> ApprovedSalesTeamAsk
  -> DECIDE_NOW + all fields present -> deal-decision -> Qualified / Disqualified
```

---

## 5. Resilience & Email Quality

LLM failure is a first-class operational concern, not an edge case.

**Layer 1 — BaseLlmClient:** HTTP 429 / 503 / IOException → retry up to 3× with exponential backoff. 400 / 401 / 403 → wrapped immediately as `LlmSendException` (no retry). Empty response → `LlmSendException`.

**Layer 2 — AiAgent:** `finally { clearHistory() }` always runs. Non-LLM exceptions propagate to the caller unchanged.

**Layer 3 — SdrRepository.tryWithAllClients():** Tries each LLM client in sequence.

| Exception type | Action |
|---|---|
| `CancellationException` | Always re-thrown (coroutine contract) |
| `LlmSendException` | Retry with next LLM client (covers all HTTP errors incl. invalid API key) |
| Any other `Exception` | Code bug — `orchestrationBug()` (always visible, no stack trace), no retry |

All clients fail → `handleOrchestrationError()` → fallback booking link → hardcoded farewell email sent directly (no LLM) → `ApprovedLlmFailed` → human queue.

```bash
# Multiple API keys = independent clients tried in sequence
GOOGLE_API_KEY=key1,key2,key3
```

**Business continuity guarantee:** In a total LLM outage, no lead is lost. Every affected lead receives a hardcoded farewell email with the fallback booking link, and appears in `interventions`.

---

## 6. Observability

Every meaningful action produces a structured event attached to the lead.

```
LeadReceived              Initial message
EmailSent                 Subject + body of every outgoing email
ReplyReceived             Full text of every inbound reply
QualificationUpdated      Which fields changed and to what value
HumanEscalationTriggered  Reason + exact triggering quote
HumanEscalationResolved   Human's verbatim response
BookingLinkCreated        Link URL + reason (AgentQualified / ClientRequestedHuman / SalesTeamHandoff / LlmFailed)
LeadDisqualified          Reason for disqualification
ProcessingFailed          LLM error reason
```

**CLI commands:** `lead`, `timeline`, `leads`, `outbox`, `events`, `interventions`, `links`

**Debug mode** — prints full agent input + full conversation history + output for every agent call:

```bash
export SDR_DEBUG=true
```

---

## 7. Open Design Question

**How do your architectural and implementation choices help the agent reliably reach the business outcome over time?**

The business objective — qualify or disqualify every inbound lead — decomposes into three sub-problems:

**1. Reliability under LLM non-determinism.**
LLMs hallucinate, time out, and return inconsistently formatted responses. The system treats these as infrastructure failures, not application errors. The qualification iron rule (`useCase != null && teamSize != null && commercialIntent != null`) is a compile-time Kotlin constraint, not a sentence in a prompt. The LLM can produce any output it wants — the state machine will not transition to `Qualified` unless the repository confirms all three fields. This is the same principle as a payment system that does not trust the frontend to say “payment succeeded” — it verifies at the database layer.

**2. State integrity across multiple turns.**
A lead conversation spans minutes or hours. The system stores all state in a `Lead` object with an append-only event log. Every agent receives `lead.toContextString()` — a deterministic serialisation of qualification fields, the full email thread, and every event. No agent ever reasons from stale or partial information. The event log also means the full audit trail is intrinsic to the data model, not reconstructed from logs.

**3. The human/AI boundary.**
The hardest reliability problem in a human-in-the-loop system is knowing when to stop and ask. The system uses two complementary mechanisms: keyword-based escalation (`escalation-detector`) for the easy case — if a lead mentions pricing, the rule fires unconditionally; and behavioural assessment (`lead-readiness`) for the harder case — if a lead is angry, uncooperative, or has exhausted the follow-up budget, the agent decides with what it has. This prevents the pathological case of an agent that asks six questions to a frustrated lead and then falls back to a human anyway.

### Tradeoffs Made Consciously

| Decision | Alternative not taken | Reason |
|---|---|---|
| Stateless orchestrator per request | Persistent agent with rolling history | Eliminates cross-lead contamination; simplifies testing |
| 11 single-purpose agents | One large agent with all capabilities | Each agent can be prompted, tested, and tuned independently |
| Iron rule in Kotlin | Iron rule in `deal-decision` prompt | Prompts can be overridden by a clever LLM; Kotlin `if` cannot |
| 4 terminal statuses | Single “approved” status | Downstream CRM needs to know *why* a lead was approved |
| `qualification-extractor` runs first | Runs after escalation | Ensures data is saved even when the pipeline stops early |
| `emailSanityChecker` as last resort | Send last draft unconditionally | Prevents empty emails while not silently failing good drafts |

---

## 8. Scaling to Thousands of Leads

Because `SdrOrchestrator` is stateless and `SdrRepository` is the single writer, scaling is a substitution problem, not a rewrite.

| Component | Development | Production |
|---|---|---|
| Lead storage | `ConcurrentHashMap` | PostgreSQL + row-level locks |
| Concurrency | Kotlin `Mutex` per lead | DB advisory lock / `SELECT FOR UPDATE` |
| Email sending | `MockEmailRepository` | SendGrid / SES |
| LLM clients | Single `GeminiLlmClient` | Pool: Gemini, OpenAI, Anthropic |
| Trigger source | CLI commands | Kafka consumer / HTTP webhook |
| Observability | CLI print | Structured JSON logs → Datadog / Grafana |

The agent pipeline itself — all 12 agents, all 6 actions, all tools — requires zero changes. `OrchestratorContext` is the isolation boundary: swap the implementation behind it, and the business logic is untouched.

---

## 9. What I Would Improve Next

**High priority**

1. **Product context in prompts** — Writers currently have no knowledge of what the product does. A `productContext: String` in `QualificationConfig` injected into all writer/reviewer prompts would eliminate generic emails.
2. **Idempotency on email sending** — A retry after a partial failure could send a duplicate email. Add a unique `messageId` per outgoing email checked before sending.
3. **Webhook receiver** — Replace the CLI with an HTTP endpoint that accepts inbound email webhooks, making the agent truly reactive.

**Medium priority**

4. **Structured LLM output** — Replace free-text agent responses with JSON Schema-constrained outputs where possible (e.g. `qualification-extractor` should return `{"teamSize": 50, "useCase": "..."}`).
5. **Prompt versioning** — Store system prompts as versioned artefacts so changes are tracked, rollbackable, and A/B testable without code deploys.
6. **Parallel tool execution** — The ReAct loop currently executes tool calls sequentially. When an agent requests multiple tools, running them with `async/awaitAll` would reduce latency.

---

## 10. Getting Started

**Prerequisites:** JDK 17+, Maven 3.8+, a Google Gemini API key.

```bash
# Single key
export GOOGLE_API_KEY=your_key_here

# Multiple keys for automatic failover (tried in sequence on failure)
export GOOGLE_API_KEY=key1,key2,key3

# Optional: detailed per-agent debug output (input prompt + full conversation history + output)
export SDR_DEBUG=true

mvn compile exec:java -Dexec.mainClass=org.example.MainKt
```

### Configuration

```kotlin
QualificationConfig(
    minTeamSize             = 10,    // below this -> Disqualified
    requireUseCase          = true,  // must describe a concrete problem
    requireCommercialIntent = true,  // must express active buying intent
    maxFollowUps            = 3,     // hard cap on follow-up emails
    maxEmailDraftRetries    = 3      // max writer/reviewer revision cycles
)
```

### CLI Commands

```
new-lead                  Register a new inbound lead
reply <email>             Submit a reply from a lead
resolve <email>           Resolve an active escalation as human reviewer
lead <email>              Full state: qualification, status, event log, email thread
timeline [email]          Chronological event timeline
leads                     All leads with status summary
outbox                    All sent emails with full content
events                    System-wide event log
interventions             Leads needing human attention
links                     All booking links grouped by status type
demo                      Run automated 3-scenario demonstration
```
