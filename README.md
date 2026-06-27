# AI SDR Agent — monday.com RevAI Engineering Challenge

> An autonomous, multi-agent Sales Development Representative that qualifies or disqualifies inbound leads through personalised email conversations — with zero lead leakage.

**Core guarantees:** zero lead leakage · hard guardrails the LLM cannot override · graceful failure handling

---

## Table of Contents

1. [Architecture: MVI & Unidirectional Data Flow](#1-architecture-mvi--unidirectional-data-flow)
2. [Statelessness: Conductor & Sub-Agents](#2-statelessness-conductor--sub-agents)
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

The system is built on the **MVI (Model-View-Intent)** pattern. The central design principle: **the business logic has zero knowledge of how input arrives or how results are displayed.** The View is a completely interchangeable adapter — today it is a CLI, but it can be replaced with a REST controller, a webhook receiver, or a Kafka consumer without touching a single line of business logic.

```
┌─────────────────────────────────────────────────────────────────────┐
│ VIEW  ·  SdrConsoleView  (dual-thread)                              │
│   Thread 1 reads stdin → fire-and-forget submit*()                 │
│   Thread 2 collects SharedFlow → prints results with separator      │
│   ↳ Replaceable with REST controller / Kafka consumer /            │
│     any adapter that calls submit*(). Zero business logic here.    │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ typed intent  (new-lead / reply / resolve)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│ INTENT  ·  SdrViewModel                                             │
│   Translates raw input into typed submit*() calls.                 │
│   No business logic — pure routing.                                │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ submit*(leadEmail, text)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│ MODEL  ·  SdrRepository  ◀── Single Source of Truth                │
│   • Owns all Lead objects; the ONLY layer allowed to mutate them   │
│   • Per-lead Mutex serialises concurrent access                    │
│   • Tries LLM clients in sequence; handles total failure           │
│   • Emits results via SharedFlow (non-blocking)                    │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ OrchestratorContext  (least-privilege scoped interface)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ORCHESTRATOR  ·  SdrOrchestrator  (stateless, ephemeral)            │
│   • Fresh instance per request; GC'd when done                     │
│   • Coordinates 12 specialised sub-agents                          │
│   • Reads state via Tools, writes state via Actions only           │
└─────────────────────────────────────────────────────────────────────┘
```

**Why MVI for an AI system:** A conventional LLM integration lets the model write to the database however it wants — producing mutations that are untestable and unpredictable. Here, **the LLM produces text; Kotlin decides what changes.** Every mutation is a named, logged `Action` (`UpdateQualificationAction`, `DisqualifyLeadAction`, etc.). No action exists that was not explicitly declared. The LLM cannot invent a new state transition.

**The View is a pure adapter.** `SdrConsoleView` calls `viewModel.submitNewLead(...)`. A `WebhookController` would call the exact same method. The business logic — all 12 agents, all policies, all resilience logic — is untouched regardless of how input arrives.

---

## 2. Statelessness: Conductor & Sub-Agents

Both the **Conductor** (`SdrOrchestrator`) and every **Sub-Agent** (`AiAgent`) are completely stateless. This is the system's primary correctness guarantee.

```
SdrRepository receives a trigger for lead@x.com
  │
  ├── acquires per-lead Mutex           (only one pipeline runs per lead at a time)
  ├── createContext(leadEmail, client)  (scoped: agent cannot read or write other leads)
  │
  ├── new SdrOrchestrator(ctx)           ← allocated here, knows nothing about past requests
  │     └── new AiAgent(config) × 12    ← each gets a fresh empty history
  │           └── processInput(prompt)  → ReAct loop → result
  │           └── clearHistory()        → always runs in finally{}, even on timeout/error
  │
  └── [SdrOrchestrator + all AiAgents are garbage collected]
      [Lead state lives only in SdrRepository, persisted across requests]
```

**Why statelessness eliminates entire classes of bugs:**

| Risk | Persistent agent | This design |
|---|---|---|
| Cross-lead context pollution | Lead A's data bleeds into Lead B's next call | Mathematically impossible — new instance each time |
| Memory / token bloat | Old conversation turns accumulate indefinitely | History cleared in `finally{}` after every call |
| Concurrency corruption | Two simultaneous replies for the same lead corrupt state | Per-lead Mutex serialises all access |
| Stale agent state | Agent "remembers" a previous wrong answer | Cannot — every call starts from zero |

**Every agent starts with full context — never blind.** Because agents are stateless, each receives a complete snapshot via `lead.toContextString()`: current status, all qualification fields, the full email thread (as a clean conversation), and the entire event history. The agent does not need memory — it reads everything fresh from the repository on each call.

---

## 3. Multi-Agent Pipeline

12 specialised sub-agents. **One agent, one decision, one responsibility.** No agent makes a decision outside its declared scope.

### Pipeline Flow

```
NEW LEAD:
  [1] spam-detector          Is this a genuine business enquiry, or junk?
  [2] qualification-extractor  Extract use_case / team_size / commercial_intent
  [3] escalation-detector    Does the message mention pricing / discounts / legal?
  [4] initial-intent-checker Wants a human / enough info to decide now / normal proceed?
  [5] info-sufficiency       Enough data to decide, or ask one more question?
  [6a] deal-decision         Qualify or disqualify (when DECIDE_NOW / DISQUALIFY_NOW)
  [6b] outreach-writer chain  Draft + review + send first email (when NEEDS_MORE_INFO)
  [*]  farewell-writer       Personalised farewell + booking link (when link issued)

REPLY:
  [1] qualification-extractor  Extract new data from the reply
  [2] escalation-detector    Does the reply mention pricing / discounts?
  [3] lead-readiness         Cooperative / wants human / decision time?
  [4] info-sufficiency       Enough data to decide, or keep qualifying?
  [5a] deal-decision         Final qualify / disqualify
  [5b] followup-writer chain  Draft + review + send follow-up email
  [*]  farewell-writer       Farewell if booking link was issued

EMAIL WRITER CHAIN (shared):
  outreach-writer / followup-writer
    → parseDraft()           strict format gate (SUBJECT: / BODY: required)
    → email-reviewer         strict: rejects placeholders, pricing mentions, >1 question
        APPROVED → send
        REJECTED → rewrite   (up to maxEmailDraftRetries — then sanity-checker decides)
    → email-sanity-checker   lenient last resort: non-empty, coherent, no bracket placeholders
        SEND → send
        FAIL → LlmSendException → tryWithAllClients (next LLM key)
```

### What Each Agent Does and Why

| Agent | When activated | What it decides | Why it exists separately |
|---|---|---|---|
| `spam-detector` | First message only | `REAL` or `SPAM` | Prevents wasting tokens on junk; defaults to `REAL` when uncertain |
| `qualification-extractor` | Every message | Calls `updateQualification` with extracted fields | Runs first so data is saved even when pipeline stops early (e.g. escalation) |
| `escalation-detector` | Every message | `SAFE` or `ESCALATED <reason>` | Keyword-based rule — pricing always escalates, no LLM discretion possible |
| `initial-intent-checker` | First message only | `PROCEED` / `CLIENT_WANTS_HUMAN` / `DECIDE_NOW` / `IRRELEVANT` | First messages have no conversation history — `lead-readiness` would have nothing to assess |
| `lead-readiness` | Replies only | `CONTINUE` / `CLIENT_WANTS_HUMAN` / `DECIDE_NOW` | Assesses multi-turn behaviour patterns — anger, disengagement, disinterest |
| `info-sufficiency` | Every pipeline | `NEEDS_MORE_INFO` / `DECIDE_NOW` / `DISQUALIFY_NOW` | Balances qualification completeness against follow-up budget remaining |
| `deal-decision` | When DECIDE_NOW | Calls `createBookingLink` or `disqualifyLead(reason)` | Only agent with access to `CreateBookingLinkAction`; iron rule enforced in Kotlin |
| `outreach-writer` | New lead, NEEDS_MORE_INFO | First email draft | Separate from follow-up because tone and purpose differ |
| `followup-writer` | Reply, NEEDS_MORE_INFO | Follow-up email draft | Knows the conversation history; asks only the next missing field |
| `email-reviewer` | After every draft | `APPROVED` or `REJECT: <reason>` | Strict quality gate — catches generic, placeholder-filled, or pricing emails |
| `email-sanity-checker` | After reviewer approval | `SEND` or `FAIL` | Lenient last resort — prevents empty emails without discarding good drafts |
| `farewell-writer` | When booking link issued | Personalised farewell email | Decoupled from main pipeline; bypasses follow-up cap; always sends |

### Key Ordering Decisions

- **`qualification-extractor` before escalation** — `"1,000 engineers, need a discount"` saves `teamSize=1000` even though it immediately triggers escalation. When the human resolves it, partial data already exists.
- **`spam-detector` first** — No tokens wasted on junk. Defaults to `REAL` (prefer to qualify a bad lead over losing a real one).
- **`email-reviewer` strict, `email-sanity-checker` lenient** — The reviewer catches quality issues early so drafts get rewritten. The sanity checker is a last-resort gate that only blocks genuinely broken output — good emails are never silently discarded.

### Agent Intelligence Configuration

Each agent's "intelligence budget" is set independently via four parameters. Currently all agents share the same Gemini model — tiers are expressed through depth and history only. Plugging a stronger model into high-stakes agents (`deal-decision`, `followup-writer`) is a one-line config change per agent.

When an agent's history approaches `History Size`, it is **not dropped** — it is passed to the LLM for summarisation and replaced with a single compressed `Summary` entry. The agent retains the gist of everything it has seen without ballooning the context window.

| Agent | Max Depth | History Size | Timeout | Tools | Actions | LLM Model (current) |
|---|---|---|---|---|---|---|
| `spam-detector` | 1 | 3 | 15 s | — | — | gemini-2.5-flash |
| `initial-intent-checker` | 1 | 5 | 15 s | — | — | gemini-2.5-flash |
| `email-sanity-checker` | 2 | 5 | 15 s | — | — | gemini-2.5-flash |
| `email-reviewer` | 2 | 5 | 20 s | — | — | gemini-2.5-flash |
| `lead-readiness` | 3 | 20 | 20 s | `getLeadState` | — | gemini-2.5-flash |
| `qualification-extractor` | 3 | 10 | 30 s | `getLeadState` | `updateQualification` | gemini-2.5-flash |
| `escalation-detector` | 3 | 15 | 30 s | `getLeadState` | `escalateToHuman` | gemini-2.5-flash |
| `farewell-writer` | 3 | 15 | 30 s | `getLeadState` | — | gemini-2.5-flash |
| `followup-writer` | 3 | 15 | 45 s | `getLeadState` | — | gemini-2.5-flash |
| `info-sufficiency` | 4 | 15 | 30 s | `getLeadState`, `checkQualification` | — | gemini-2.5-flash |
| `outreach-writer` | 4 | 15 | 60 s | `getLeadState` | — | gemini-2.5-flash |
| `deal-decision` | 5 | 20 | 60 s | `getLeadState`, `checkQualification` | `createBookingLink`, `disqualifyLead` | gemini-2.5-flash |

`Max Depth` = maximum ReAct reasoning rounds before the agent is forced to answer. `History Size` = turns kept before summarisation triggers.

### Terminal States & The Four-Link Design

A lead reaches exactly **one of four terminal states**. Four distinct booking URLs were a deliberate design choice: a single `approved` status loses information that downstream CRM routing needs. The reason a lead was approved determines which sales queue they enter, what SLA applies, and what the rep's opening script looks like.

| Status | Booking URL | When issued | Business routing |
|---|---|---|---|
| `Qualified` | `/qualified/{id}` | Agent confirmed all 3 fields | High-intent AE queue |
| `ApprovedClientAsk` | `/client-ask/{id}` | Lead explicitly requested a human | Immediate callback queue |
| `ApprovedSalesTeamAsk` | `/sales-team/{id}` | Sales rep closed escalation, data incomplete | SDR review queue |
| `ApprovedLlmFailed` | `/fallback/{id}` | All LLM clients failed | Manual intervention queue |

Each `BookingLinkCreated` event records a `BookingLinkReason` enum value — the audit log always explains *why* a link was issued, not just *that* it was.

---

## 4. Guardrails, Policies & Human-in-the-Loop

Business policies live in the **execution layer** — enforced in Kotlin before any agent runs. The LLM has zero discretion over these rules.

### Hard Policies

| Policy | Enforcement |
|---|---|
| No duplicate outreach | `if (lead.outreachSent) return` in `SendOutreachEmailAction` |
| Hard follow-up cap | `remainingFollowUps == 0` forces `DECIDE_NOW` — lead never silently abandoned |
| Booking links require qualification | `CreateBookingLinkAction` registered only on `deal-decision`; no other agent can call it |
| Pricing forces escalation | `Escalated` status blocks pipeline on every entry; no prompt can bypass |
| Farewell email always sent | On every booking link issuance, `farewell-writer` sends a closing email — bypasses follow-up cap; hardcoded template if LLM fails |

**Iron Rule (Kotlin, not prompt):**
```kotlin
// Qualified is unreachable unless all three fields are non-null.
// No prompt engineering can override this check.
val hasAllFields = lead.useCase != null &&
                   lead.teamSize != null &&
                   lead.commercialIntent != null
```

### Escalation Triggers

| Trigger | Agent | Outcome |
|---|---|---|
| Pricing / discounts / budget | `escalation-detector` | `Escalated` — pipeline blocked until human resolves |
| Lead asks for a human | `initial-intent-checker` or `lead-readiness` | `ApprovedClientAsk` — terminal, farewell sent |
| Anger / frustration / disengagement | `lead-readiness` | `ApprovedClientAsk` — terminal |

### Human Intent Detection on Resolution

When a human resolves an escalation, `info-sufficiency` interprets their **intent** — not just their words:

```
Human: "10% discount approved"
  → NEEDS_MORE_INFO  (answered a pricing question, not closing the lead)
  → followup-writer relays the answer + asks the next missing qualification field

Human: "Forward this lead to sales"
  → DECIDE_NOW + data incomplete   → ApprovedSalesTeamAsk + farewell
  → DECIDE_NOW + all fields known  → deal-decision → Qualified or Disqualified
```

---

## 5. Resilience & Email Quality

LLM failure is a first-class operational concern, not an edge case.

**Layer 1 — BaseLlmClient:** HTTP 429 / 503 / IOException → retry up to 3× with exponential backoff. 400 / 401 / 403 (including invalid API key) → wrapped immediately as `LlmSendException`, no retry. Empty response → `LlmSendException`.

**Layer 2 — AiAgent:** `finally { clearHistory() }` always runs — even on timeout or exception.

**Layer 3 — SdrRepository.tryWithAllClients():** Tries each configured LLM client in sequence.

| Exception type | Action |
|---|---|
| `CancellationException` | Always re-thrown (coroutine contract) |
| `LlmSendException` | Log failure, retry with next LLM client |
| Any other `Exception` | Code bug — `orchestrationBug()` prints one red line (no stack trace), no retry |

All clients fail → `handleOrchestrationError()` → fallback booking link created → **hardcoded farewell email sent directly (no LLM required)** → `ApprovedLlmFailed` → lead appears in `interventions`.

```bash
# Multiple API keys = independent clients tried in sequence on failure
GOOGLE_API_KEY=key1,key2,key3
```

**Business continuity guarantee:** In a total LLM outage, no lead is lost. Every affected lead receives a farewell email with a fallback booking link, and a sales rep can see them under `interventions`.

---

## 6. Observability

Every meaningful action produces a structured event attached to the lead. Agents receive the full event history on every call — it is their source of truth for what has already happened.

```
LeadReceived              Initial message
EmailSent                 Subject + full body of every outgoing email
ReplyReceived             Full text of every inbound reply
QualificationUpdated      Which fields changed and to what value
HumanEscalationTriggered  Reason + exact triggering quote
HumanEscalationResolved   Human's verbatim response
BookingLinkCreated        Link URL + reason enum (AgentQualified / ClientRequestedHuman / SalesTeamHandoff / LlmFailed)
LeadDisqualified          Reason for disqualification
ProcessingFailed          LLM error reason
```

**CLI commands:** `lead`, `events`, `all-events`, `leads`, `outbox`, `inbox`, `interventions`, `links`

**Debug mode** — prints full agent input + complete conversation history + output for every sub-agent call:

```bash
export SDR_DEBUG=true
```

---

## 7. Open Design Question

**How do your architectural and implementation choices help the agent reliably reach the business outcome over time?**

The business objective — qualify or disqualify every inbound lead — decomposes into three reliability sub-problems:

**1. Reliability under LLM non-determinism.**
LLMs hallucinate, time out, and return inconsistently formatted responses. The system treats these as infrastructure failures, not application errors. The qualification iron rule (`useCase != null && teamSize != null && commercialIntent != null`) is a compile-time Kotlin constraint, not a sentence in a prompt. The LLM can produce any output it wants — the state machine will not transition to `Qualified` unless the repository confirms all three fields. This is the same principle as a payment system that does not trust the frontend to say "payment succeeded" — it verifies at the database layer.

**2. State integrity across multiple turns.**
A lead conversation spans minutes or hours. The system stores all state in a `Lead` object with an append-only event log. Every agent receives `lead.toContextString()` — a deterministic serialisation of qualification fields, the full email thread as a clean conversation, and every event. No agent ever reasons from stale or partial information. The event log also means the full audit trail is intrinsic to the data model, not reconstructed from logs.

**3. The human / AI boundary.**
The hardest reliability problem in a human-in-the-loop system is knowing when to stop and ask. The system uses two complementary mechanisms: keyword-based escalation (`escalation-detector`) for the easy case — if a lead mentions pricing, the rule fires unconditionally; and behavioural assessment (`lead-readiness`) for the harder case — if a lead is angry, uncooperative, or has exhausted the follow-up budget, the agent decides with what it has. This prevents the pathological case of an agent that asks six questions to a frustrated lead and then falls back to a human anyway.

### Tradeoffs Made Consciously

| Decision | Alternative not taken | Reason |
|---|---|---|
| Stateless orchestrator + agents per request | Persistent agent with rolling history | Eliminates cross-lead contamination; simplifies testing; no memory leaks |
| 12 single-purpose agents | One large agent with all capabilities | Each agent can be prompted, tested, and tuned independently |
| Iron rule in Kotlin | Iron rule in `deal-decision` prompt | Prompts can be overridden by a clever LLM; `if (hasAllFields)` in Kotlin cannot |
| 4 terminal statuses with distinct URLs | Single "approved" status | Downstream CRM needs to know *why* a lead was approved to route correctly |
| `qualification-extractor` runs before escalation | Runs after | Saves partial data even when the pipeline stops early |
| View decoupled via MVI | LLM calls driven by HTTP handler directly | Business logic survives any input-source change without modification |

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

4. **Tiered model intelligence** — Currently all 12 agents share the same single Gemini model. Intelligence tiers are expressed only through `maxDepth` (reasoning rounds) and `maxHistorySize` (context window) per agent. The natural next step is to back different agents with different models — a fast, cheap model for binary decisions (`spam-detector`, `email-sanity-checker`) and a more capable model for high-stakes reasoning (`deal-decision`, `followup-writer`). The `AgentConfig.llmClient` field already supports this; it is a configuration change, not an architectural one.
5. **Structured LLM output** — Replace free-text agent responses with JSON Schema-constrained outputs (e.g. `qualification-extractor` returns `{"teamSize": 50, "useCase": "..."}`)
6. **Prompt versioning** — Store system prompts as versioned artefacts so changes are tracked, rollbackable, and A/B testable without code deploys.
7. **Parallel tool execution** — The ReAct loop currently runs tool calls sequentially. When an agent requests multiple tools, running them with `async/awaitAll` would reduce latency.

---

## 10. Getting Started

**Prerequisites:** JDK 17+, Maven 3.8+, a Google Gemini API key.

```bash
# Single key
export GOOGLE_API_KEY=your_key_here

# Multiple keys for automatic failover (tried in sequence on failure)
export GOOGLE_API_KEY=key1,key2,key3

# Optional: full per-agent debug output (input + conversation history + output)
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
new-lead                  Submit a new inbound lead
reply     <email>         Simulate a lead reply
resolve   <email>         Provide human escalation response
leads                     List all leads with status
lead      <email>         Full lead detail: qualification, status, events, email thread
events    <email>         Event timeline for a specific lead
all-events                Global system event log across all leads
outbox                    All sent emails with full content
inbox                     All received emails
links                     All booking links grouped by status type
interventions             Leads needing human attention
demo                      Run automated 3-scenario demonstration
help                      Show this command list
exit / quit               Exit the CLI
```
