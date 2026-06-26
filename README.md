# AI SDR Agent — monday.com RevAI Engineering Challenge

> An autonomous, multi-agent Sales Development Representative that qualifies or disqualifies inbound leads through personalised email conversations.  
> Built for **zero lead leakage**, deterministic state mutations, full observability, and graceful degradation under LLM failure.

---

## Table of Contents

1. [Challenge Requirements — Coverage Map](#1-challenge-requirements--coverage-map)
2. [Architecture: MVI & Unidirectional Data Flow](#2-architecture-mvi--unidirectional-data-flow)
3. [The Power of Statelessness](#3-the-power-of-statelessness)
4. [Multi-Agent Pipeline](#4-multi-agent-pipeline)
5. [Terminal States & Booking Link Taxonomy](#5-terminal-states--booking-link-taxonomy)
6. [Hard Guardrails & Execution Boundaries](#6-hard-guardrails--execution-boundaries)
7. [Human-in-the-Loop & Manual Handoffs](#7-human-in-the-loop--manual-handoffs)
8. [Email Quality Pipeline: Writer → Reviewer → Sanity Checker](#8-email-quality-pipeline-writer--reviewer--sanity-checker)
9. [Resilience & Graceful Degradation](#9-resilience--graceful-degradation)
10. [Observability](#10-observability)
11. [Open Design Question](#11-open-design-question)
12. [Scaling to Thousands of Leads](#12-scaling-to-thousands-of-leads)
13. [What I Would Improve Next](#13-what-i-would-improve-next)
14. [Getting Started](#14-getting-started)

---

## 1. Challenge Requirements — Coverage Map

The table below maps every explicit requirement to exactly where it is implemented.

| Challenge Requirement | Status | Implementation |
|---|---|---|
| Receives a new lead | ✅ | `SdrRepository.submitNewLead()` → `SdrOrchestrator.process()` |
| Sends a personalised outreach email | ✅ | `outreach-writer` agent + `emailReviewer` + `emailSanityChecker` |
| Processes incoming replies | ✅ | `SdrRepository.submitReply()` → same unified `process()` pipeline |
| Extracts qualification info | ✅ | `qualification-extractor` agent (runs first in every pipeline pass) |
| Decides: qualified / disqualified / needs more | ✅ | `info-sufficiency` → `deal-decision` (iron rule enforced in Kotlin code) |
| Continues until qualified or disqualified | ✅ | Follow-up loop with hard `maxFollowUps` cap |
| Booking link issued on qualification | ✅ | `CreateBookingLinkAction` — only reachable after all 3 fields confirmed |
| Collect: use case, team size, commercial intent | ✅ | `QualificationConfig` + `UpdateQualificationAction` |
| Qualification logic configurable | ✅ | `QualificationConfig.kt` — all thresholds are data, not code |
| Pricing/discounts → human escalation | ✅ | `escalation-detector` agent + `EscalateToHumanAction` |
| Human must respond before agent continues | ✅ | `Escalated` status blocks all pipeline processing |
| ≥ 2 meaningful policies / guardrails | ✅ | 4 hard-coded policies (see §6) |
| State preserved across steps | ✅ | `Lead` object + immutable event log; full `toContextString()` |
| Avoid unsafe state mutations | ✅ | MVI pattern; LLM can only call declared `Action` functions |
| Observability: emails, replies, decisions | ✅ | `events`, `timeline`, `outbox`, `lead` CLI commands |
| README: architecture + design decisions | ✅ | This document |
| README: what to improve next | ✅ | §13 |
| Open design question | ✅ | §11 |
| Scaling question | ✅ | §12 |

### Beyond the Requirements

| Additional Feature | Rationale |
|---|---|
| 4-tier terminal status + 4 distinct booking links | Downstream CRM can route leads without ambiguity |
| Multi-LLM client failover (comma-separated keys) | Business continuity — zero leads lost even in full API outage |
| `ApprovedLlmFailed` terminal status + fallback link | Every lead gets a URL even if AI never made a decision |
| `lead-readiness` agent (CLIENT_WANTS_HUMAN / DECIDE_NOW) | Respects angry/frustrated leads; forces decision when budget exhausted |
| `emailSanityChecker` fallback after strict review | Prevents sending empty emails while still catching truly bad drafts |
| Per-lead coroutine Mutex | Concurrent replies to the same lead can never corrupt state |
| `interventions` command | Human queue for all leads needing manual attention |

---

## 2. Architecture: MVI & Unidirectional Data Flow

The system is structured around the **MVI (Model-View-Intent)** pattern. All state changes are unidirectional, predictable, and fully auditable.

```
┌─────────────────────────────────────────────────────────────────────┐
│  USER INPUT / WEBHOOK                                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ raw text command
                               ▼
╔═════════════════════════════════════════════════════════════════════╗
║  VIEW  │  SdrConsoleView                                           ║
║                                                                     ║
║  • Renders lead list, timelines, outbox, event log                 ║
║  • Parses CLI input into typed method calls on the ViewModel       ║
║  • Never reads from or writes to any repository directly           ║
╚══════════════════════════════╦══════════════════════════════════════╝
                               ║ typed intent call
                               ▼
╔═════════════════════════════════════════════════════════════════════╗
║  INTENT  │  SdrViewModel                                           ║
║                                                                     ║
║  • Translates raw inputs into business intents                     ║
║  • Subscribes to the result SharedFlow from the Repository         ║
║  • Formats results for display — no business logic here            ║
╚══════════════════════════════╦══════════════════════════════════════╝
                               ║ submit*(lead/reply/resolve)
                               ▼
╔═════════════════════════════════════════════════════════════════════╗
║  MODEL  │  SdrRepository  ← Single Source of Truth                ║
║                                                                     ║
║  • Owns all Lead objects — no other layer may mutate them          ║
║  • Per-lead Mutex: only one pipeline runs per lead at a time       ║
║  • Tries all LLM clients in order; handles total failure           ║
║  • Emits results via SharedFlow (non-blocking, hot stream)         ║
╚══════════════════════════════╦══════════════════════════════════════╝
                               ║ OrchestratorContext
                               ║ (least-privilege interface)
                               ▼
╔═════════════════════════════════════════════════════════════════════╗
║  ORCHESTRATOR  │  SdrOrchestrator  (stateless, ephemeral)         ║
║                                                                     ║
║  • Instantiated fresh for every request; GC'd immediately after    ║
║  • Coordinates 9 specialised agents in a defined pipeline          ║
║  • State reads  → via Tools    (read-only view of the Lead)        ║
║  • State writes → via Actions  (validated, repository-routed)      ║
╚═════════════════════════════════════════════════════════════════════╝
```

### Why MVI Matters for an AI System

A conventional LLM integration lets the model write to the database however it wants. This creates a class of bugs that are impossible to unit-test and hard to reason about. By enforcing MVI:

- **The LLM can only call declared `Action` functions.** It cannot invent a new state transition.
- **Every mutation is a named, logged event.** `UpdateQualificationAction` writes one specific field. `EscalateToHumanAction` creates one specific escalation record. Nothing else.
- **The repository is the only writer.** All agents share the same read/write contract via `OrchestratorContext`, a narrow interface that deliberately exposes only what each orchestrator needs.

---

## 3. The Power of Statelessness

Both `SdrOrchestrator` and `AiAgent` are **completely stateless**. This is the system's primary correctness guarantee.

### Per-Request Lifecycle

```
SdrRepository.submitReply("lead@x.com", "message")
    │
    ├─ acquires per-lead Mutex  (blocks duplicate concurrent calls)
    ├─ creates OrchestratorContext (scoped to this lead + this LLM client)
    ├─ new SdrOrchestrator(ctx)         ← allocated here
    │      └─ new AiAgent(config) × 9  ← one per agent, fresh history each
    │              │
    │              ├─ processInput()    ← runs pipeline
    │              └─ clearHistory()   ← called in finally{}, always runs
    │
    └─ [Orchestrator + all Agents are garbage collected]
```

### What Statelessness Prevents

| Risk | Traditional Agent | This Design |
|---|---|---|
| Cross-lead context pollution | Lead A's details bleed into Lead B's response | Impossible — new instance per request |
| History bloat | Old turns inflate token cost indefinitely | History cleared in `finally{}` block |
| Concurrency corruption | Two replies to same lead corrupt each other | Per-lead Mutex serialises access |
| Memory leaks | Agents accumulate in memory over time | GC collects them immediately |

### Full Context Injection — No Blind Starts

Because agents are stateless, every decision agent receives the **full lead context directly in its initial prompt**. Agents never start a reasoning loop without knowing what has already happened:

```kotlin
escalationDetector.processInput(
    "Lead email: $scopedLeadEmail\n" +
    "Incoming message:\n\"$messageText\"\n\n" +
    leadContext   // ← full toContextString(): status, thread, events, qualification
)
```

Tool calls (`getLeadState`) remain available as a mid-run refresh mechanism for agents that may mutate state during their loop (e.g. `deal-decision` after `createBookingLink`).

---

## 4. Multi-Agent Pipeline

The system uses **9 specialised agents**, each with a single responsibility. This is the iron rule: *one agent, one decision, one action*.

### Unified Pipeline (New Lead and Reply share one entry point)

```
Incoming message  ──  new lead (inboundMessage) or reply text
         │
         ▼
[1] qualification-extractor   Extracts teamSize / useCase / commercialIntent
         │                    from the message. Runs FIRST — fields are populated
         │                    even if the pipeline short-circuits after this step.
         ▼
[2] escalation-detector       Detects pricing / discount / legal triggers.
         │                    Context-aware: knows what the SDR asked previously.
         │                    → ESCALATED (halts) or SAFE (continues)
         ▼
[3] lead-readiness            Reads the full event history. Outputs one token:
         │                    CLIENT_WANTS_HUMAN → ApprovedClientAsk  (terminal)
         │                    DECIDE_NOW         → deal-decision       (terminal)
         │                    GATHER_MORE        → continues pipeline
         ▼
[4] info-sufficiency          Checks qualification completeness vs. follow-up budget.
         │                    DISQUALIFY_NOW → deal-decision
         │                    DECIDE_NOW     → deal-decision
         │                    NEEDS_MORE_INFO → email writer path
         ▼
    ┌────┴─────────────────────────────────────┐
    │                                          │
    ▼                                          ▼
[5a] deal-decision                    [5b] outreach-writer / followup-writer
     Makes QUALIFIED / DISQUALIFIED          Writes the next email draft.
     Iron rule: all 3 fields required        ↓
     for Qualified (enforced in code,    [6] email-reviewer  (strict)
     not by the LLM).                        APPROVED → send
                                             REJECTED → rewrite (up to maxEmailDraftRetries)
                                             ↓  (if all retries exhausted)
                                         [7] email-sanity-checker  (lenient)
                                             SEND → send (imperfect but safe)
                                             FAIL → throw → tryWithAllClients
                                                    → ApprovedLlmFailed
```

### Why Qualification Extraction Runs First

A critical ordering decision: `qualification-extractor` runs **before** escalation detection. This means that even if a message triggers an escalation ("we need a discount for our 1,000-person team"), the fields `teamSize=1000` and `commercialIntent=true` are already saved before the pipeline halts. When a human resolves the escalation, the lead already has more data than zero.

### Agent Configuration

| Agent | Role | Max Depth | Timeout | History |
|---|---|---|---|---|
| `outreach-writer` | First email | 4 | 60s | 15 |
| `followup-writer` | Subsequent emails | 3 | 45s | 15 |
| `email-reviewer` | Strict quality gate | 2 | 20s | 5 |
| `email-sanity-checker` | Last-resort safety gate | 2 | 15s | 5 |
| `escalation-detector` | Pricing/legal triggers | 3 | 30s | 15 |
| `lead-readiness` | Behaviour assessment | 3 | 20s | 20 |
| `qualification-extractor` | Field extraction | 3 | 30s | 10 |
| `info-sufficiency` | Completeness check | 4 | 30s | 15 |
| `deal-decision` | Final qualify/disqualify | 5 | 60s | 20 |

---

## 5. Terminal States & Booking Link Taxonomy

A lead reaches exactly **one of four terminal states**. Each state produces a **distinct booking link URL** — downstream CRM systems receive unambiguous routing signals.

```
LeadStatus (Kotlin sealed interface — compiler enforces exhaustive handling)
│
├── New                     initial, pre-outreach
├── AwaitingClientResponse  email sent, waiting for reply
├── Escalated               paused — a human must act before pipeline resumes
│
└── TERMINAL ──────────────────────────────────────────────────────────────
    │
    ├── Qualified              /qualified/{id}
    │     All 3 fields present. Agent decision. Standard sales booking.
    │
    ├── ApprovedClientAsk      /client-ask/{id}
    │     Lead explicitly requested a human. Immediately honoured.
    │     Dedicated channel — lead self-referred, not agent-qualified.
    │
    ├── ApprovedSalesTeamAsk   /sales-team/{id}
    │     Sales rep closed escalation with a forward intent,
    │     but qualification data was incomplete.
    │     Internal handoff — not agent-qualified.
    │
    └── ApprovedLlmFailed      /fallback/{id}
          All LLM clients exhausted. No AI decision possible.
          Lead preserved — human handles manually via 'interventions'.
```

**Iron Rule (enforced in Kotlin, not by the LLM):**

```kotlin
// resolveEscalation() — this check is in code, not in a prompt
val hasAllFields = lead.useCase != null &&
                   lead.teamSize != null &&
                   lead.commercialIntent != null
```

`Qualified` is unreachable unless all three fields are non-null. No prompt engineering can override this.

---

## 6. Hard Guardrails & Execution Boundaries

Business policies live in the **execution layer** — enforced in Kotlin before any agent runs. The LLM has zero discretion over these rules.

### Policy 1 — No Duplicate Outreach
```kotlin
// SendOutreachEmailAction.kt
if (lead.outreachSent) return "Outreach already sent to ${lead.email}."
```
A lead can only receive one initial outreach. All subsequent emails are follow-ups.

### Policy 2 — Hard Follow-up Cap
```kotlin
// QualificationConfig.kt
val maxFollowUps: Int = 3
```
When `remainingFollowUps = 0`, `info-sufficiency` is explicitly told to output `DECIDE_NOW`. The agent makes the best call with available data — the lead is never silently abandoned.

### Policy 3 — Booking Links Require Qualification
`createBookingLink()` is only reachable via `CreateBookingLinkAction`, which is only registered on the `deal-decision` agent. No other agent can call it.

### Policy 4 — Pricing Triggers Mandatory Human Escalation
`escalation-detector` + `EscalateToHumanAction`. Once escalated, the lead status becomes `Escalated` — a sealed state that the pipeline checks first on every entry:
```kotlin
is LeadStatus.Escalated -> {
    ctx.emitResult("⛔ Awaiting human review. Use: resolve $email")
    return
}
```

### Configurable Thresholds (all data, no code changes needed)
```kotlin
data class QualificationConfig(
    val minTeamSize: Int = 10,
    val requireUseCase: Boolean = true,
    val requireCommercialIntent: Boolean = true,
    val maxFollowUps: Int = 3,
    val maxEmailDraftRetries: Int = 3,
    val pricingKeywords: List<String> = listOf(
        "price", "pricing", "cost", "discount", "budget", "how much", ...
    )
)
```

---

## 7. Human-in-the-Loop & Manual Handoffs

### Escalation Triggers

| Trigger | Detected By | Outcome |
|---|---|---|
| Pricing, discounts, budget negotiation | `escalation-detector` + keyword context | `Escalated` — pipeline blocked |
| Lead explicitly asks for a human | `lead-readiness` → `CLIENT_WANTS_HUMAN` | `ApprovedClientAsk` — terminal, no further contact |
| Lead is angry / hostile / uncooperative | `lead-readiness` → `CLIENT_WANTS_HUMAN` | Same — immediately respected |
| Sales rep manually overrides | `resolve <email> <response>` CLI command | Human response interpreted; pipeline continues or closes |

### Escalation Resolution — Human Intent Detection

When a human resolves an escalation, the system distinguishes between two intents:

```
Human response
      │
      ▼
infoSufficiency interprets intent:
      │
      ├── "forward / approve / close lead"    → DECIDE_NOW
      │         │
      │         ├── all 3 fields present  → deal-decision → Qualified / Disqualified
      │         └── fields missing        → ApprovedSalesTeamAsk (link issued)
      │
      └── "answered a specific question"     → NEEDS_MORE_INFO
                │
                └── followup-writer: relay human's answer to lead
                    + ask the next missing qualification question
```

**Example:** Human responds *"10% discount approved"* — the system correctly identifies this as answering a question, not closing the lead. The next email says *"Good news — we can offer you a 10% discount. Could you also share your team size?"* and the qualification loop continues.

---

## 8. Email Quality Pipeline: Writer → Reviewer → Sanity Checker

Email generation uses a **three-tier quality chain** to balance quality enforcement against LLM unreliability.

```
writerPrompt
    │
    ▼  (up to maxEmailDraftRetries times)
┌─────────────────────────────────────────────────────┐
│  outreach-writer / followup-writer                  │
│  Generates SUBJECT: / BODY: format draft            │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼ parseDraft()
               null? ──→ feedback: "wrong format", retry
                       │
                       ▼
┌─────────────────────────────────────────────────────┐
│  email-reviewer  (STRICT)                           │
│  Rejects: placeholders, multiple questions,         │
│  pricing mention, generic filler, bad tone          │
└──────────────────────┬──────────────────────────────┘
          APPROVED ────┘     REJECTED ──→ feedback, retry
                       
────── all retries exhausted ──────────────────────────
                       │
              no valid draft? ──→ throw (LLM likely broken)
                       │
              best parseable draft ↓
                       │
┌─────────────────────────────────────────────────────┐
│  email-sanity-checker  (LENIENT)                    │
│  Only checks: non-empty, no placeholders,           │
│  coherent, not embarrassing                         │
└──────────────────────┬──────────────────────────────┘
         SEND ─────────┘     FAIL ──→ throw
                                       │
                               tryWithAllClients
                               tries next LLM client
                                       │
                               all clients failed
                                       ▼
                               ApprovedLlmFailed
```

**Engineering tradeoff:** The strict reviewer catches quality problems while the system has LLM capacity. The lenient sanity checker is a last resort — it accepts imperfect emails as long as they are not harmful. This prevents the failure mode where a fine email is sent with an empty subject line because the reviewer kept rejecting it and the code returned `Pair("", "")` after exhausted retries.

---

## 9. Resilience & Graceful Degradation

LLM failure is a first-class operational concern, not an edge case.

### Three-Layer Retry Architecture

```
Layer 1 — BaseLlmClient.executeWithRetry()
  ├── HTTP 429 / 503 / IOException  → retry up to 3× with exponential backoff
  ├── HTTP 400 / 401 / 403          → fail-fast (won't recover)
  └── Empty / blank response        → throw IllegalStateException
                │
                ▼
Layer 2 — AiAgent.processInput()
  ├── Only catches CancellationException (coroutine contract — always re-throw)
  ├── finally { clearHistory() }    → always runs, regardless of failure path
  └── All other exceptions propagate to the caller
                │
                ▼
Layer 3 — SdrRepository.tryWithAllClients()
  ├── Tries client[0] → fail → tries client[1] → ...
  └── ALL clients failed:
        handleOrchestrationError()
          → createFallbackBookingLink()
          → lead.status = ApprovedLlmFailed(fallbackLink)
          → logEvent(ProcessingFailed)
          → emitResult() — lead is safe, human queue updated
```

### Multiple API Key Failover

```bash
GOOGLE_API_KEY=key1,key2,key3
```

Each key is an independent `GeminiLlmClient`. They are tried in sequence. The system only escalates to `ApprovedLlmFailed` when every key has been exhausted.

### Business Continuity Guarantee

> In a total LLM outage, **no lead is lost and no lead is silently dropped.** Every affected lead receives a fallback booking link and appears in the `interventions` queue. A human can pick it up immediately.

---

## 10. Observability

Every meaningful action in the system produces a structured, inspectable event.

### CLI Commands

```
lead <email>         Full state snapshot: status, qualification fields, booking link,
                     complete event history, full email thread.

timeline <email>     Chronological event log with timestamps and human-readable detail.

events               System-wide event log across all leads.

outbox               All sent emails with full subject + body.

leads                Lead list with status, follow-up count, booking links.

interventions        Leads requiring human attention: Escalated + ApprovedLlmFailed.
```

### What Is Logged on Every Lead

```
[LeadReceived]              Initial message
[EmailSent]                 Subject + body of every outgoing email
[ReplyReceived]             Full text of every inbound reply
[QualificationUpdated]      Which fields changed and to what value
[HumanEscalationTriggered]  Reason + exact triggering quote
[HumanEscalationResolved]   Human's verbatim response
[BookingLinkCreated]        Link URL + status type
[ProcessingFailed]          LLM error reason
```

### Sample `timeline` Output

```
══════════════════════════════════════
  Timeline — lead@company.com
══════════════════════════════════════
  09:01:00  🆕  LeadReceived           "Hi, we need help with our pipeline"
  09:01:05  📧  EmailSent              subject="Quick question about your team"
  09:04:22  📨  ReplyReceived          "We have 50 engineers, mostly backend"
  09:04:23  🔍  QualificationUpdated   teamSize=50
  09:04:28  📧  EmailSent              subject="Following up on your use case"
  09:05:44  📨  ReplyReceived          "Can we get a discount?"
  09:05:45  🚨  HumanEscalationTriggered  reason="Pricing discussion"
  09:07:10  👤  HumanEscalationResolved   response="10% approved"
  09:07:15  📧  EmailSent              subject="Great news on your discount"
  09:08:30  📨  ReplyReceived          "We want to build internal tooling"
  09:08:31  🔍  QualificationUpdated   useCase="internal tooling", commercialIntent=true
  09:08:36  🔗  BookingLinkCreated     https://booking.example.com/qualified/abc123
══════════════════════════════════════
```

---

## 11. Open Design Question

**How do your architectural and implementation choices help the agent reliably reach the business outcome over time?**

### Business Objective → Engineering Contract

The business objective — qualify or disqualify every inbound lead — decomposes into three sub-problems that require different engineering responses:

**1. Reliability under LLM non-determinism**

LLMs hallucinate, time out, and return inconsistently formatted responses. The system treats these as infrastructure failures, not application errors. The qualification iron rule (`useCase != null && teamSize != null && commercialIntent != null`) is a compile-time Kotlin constraint, not a sentence in a prompt. The LLM can produce any output it wants — the state machine will not transition to `Qualified` unless the repository confirms all three fields. This is the same principle as a payment system that does not trust the frontend to say "payment succeeded" — it verifies it at the database layer.

**2. State integrity across multiple turns**

A lead conversation spans minutes or hours across potentially many agent invocations. The system stores all state in a `Lead` object with an append-only event log. Every agent that receives context gets `lead.toContextString()` — a deterministic serialisation that includes qualification fields, the full email thread, and every event. This means no agent ever reasons from stale or partial information. The event log also means the full audit trail is intrinsic to the data model, not reconstructed from logs.

**3. Human/AI boundary**

The hardest reliability problem in a human-in-the-loop system is knowing when to stop and ask. The system uses two complementary mechanisms:
- **Keyword-based escalation** (`escalation-detector`) for the easy case: if a lead mentions pricing, the rule fires unconditionally.
- **Behavioural assessment** (`lead-readiness`) for the harder case: if a lead is angry, uncooperative, or has exhausted the follow-up budget, the agent is instructed to decide with what it has rather than keep asking. This prevents the pathological case of an agent that asks six questions to a frustrated lead and then falls back to a human anyway.

### Tradeoffs Made Consciously

| Decision | Alternative Not Taken | Reason |
|---|---|---|
| Stateless orchestrator per request | Persistent agent with rolling history | Eliminates cross-lead contamination; simplifies testing |
| 9 single-purpose agents | One large agent with all capabilities | Each agent can be prompted, tested, and tuned independently |
| Iron rule in Kotlin code | Iron rule in `deal-decision` prompt | Prompts can be overridden by a clever LLM; Kotlin `if` cannot |
| 4 terminal statuses | Single "approved" status | Downstream CRM needs to know *why* a lead was approved |
| `qualificationExtractor` runs first | Runs after escalation check | Ensures data is saved even when pipeline stops early |
| `emailSanityChecker` as last resort | Send last draft unconditionally | Prevents empty emails while not silently failing good drafts |

---

## 12. Scaling to Thousands of Leads

Because `SdrOrchestrator` is stateless and `SdrRepository` is the single writer, scaling is a substitution problem, not a rewrite.

**Two substitutions are all that is required:**

```
Current (single process):
  CLI → SdrViewModel → SdrRepository (HashMap) → Kotlin Coroutine per lead

Production (horizontally scaled):
  Webhook (monday.com / CRM) → Kafka topic "lead-events"
         │
         └── N worker pods, each running SdrRepository
               │
               ├── PostgreSQL (replaces HashMap — row-level lock replaces Mutex)
               ├── SendGrid / SES (replaces MockEmailRepository)
               └── Gemini + OpenAI pool (already supported via List<LlmClient>)
```

| Component | Development | Production |
|---|---|---|
| Lead storage | `ConcurrentHashMap` | PostgreSQL + row-level locks |
| Concurrency | Kotlin `Mutex` per lead | DB-level advisory lock / `SELECT FOR UPDATE` |
| Email sending | `MockEmailRepository` | SendGrid / SES |
| LLM clients | Single `GeminiLlmClient` | Pool: Gemini, OpenAI, Anthropic |
| Trigger source | CLI commands | Kafka consumer / HTTP webhook |
| Observability | CLI print | Structured JSON logs → Datadog / Grafana |

The agent pipeline itself — all 9 agents, all 6 actions, all tools — requires zero changes. The `OrchestratorContext` interface is the isolation boundary: swap the implementation behind it, and the business logic is untouched.

---

## 13. What I Would Improve Next

### High Priority (production blockers)

1. **Product context in agent prompts** — Writers currently have no knowledge of what the product does. A `productContext: String` field in `QualificationConfig` would be injected into all writer/reviewer system prompts, eliminating placeholder-style generic emails.

2. **Idempotency keys on email sending** — A retry after a partial failure could send a duplicate email. Add a unique `messageId` per outgoing email checked before sending.

3. **Webhook receiver** — Replace the CLI with an HTTP endpoint that accepts inbound email webhooks (e.g. from SendGrid Inbound Parse or monday.com automation), making the agent truly reactive.

### Medium Priority (quality improvements)

4. **Structured LLM output** — Replace free-text agent responses with JSON Schema-constrained outputs where possible (e.g. `qualification-extractor` should return `{"teamSize": 50, "useCase": "..."}` not prose).

5. **Prompt versioning** — Store system prompts as versioned artefacts (YAML or database rows) so prompt changes are tracked, rollbackable, and A/B testable without code deploys.

6. **Observability pipeline** — Emit structured JSON events to a stream (Kafka, CloudWatch) rather than printing to stdout. Enables real-time dashboards, alerting on escalation rate, and LLM latency tracking.

### Low Priority (nice to have)

7. **Lead scoring** — Instead of binary qualified/disqualified, compute a score from qualification signals and let the human set the threshold via config.

8. **Multi-language support** — The system already handles Hebrew replies (as seen in tests). Formalising language detection and ensuring agent prompts work in the lead's language would be straightforward.

---

## 14. Getting Started

### Prerequisites

- JDK 17+
- Maven 3.8+
- A Google Gemini API key

### Run

```bash
# Single key
export GOOGLE_API_KEY=your_key_here

# Multiple keys for automatic failover
export GOOGLE_API_KEY=key1,key2,key3

mvn compile exec:java -Dexec.mainClass=org.example.MainKt
```

### CLI Commands

```
new-lead                  Register a new inbound lead (prompts for name/email/company/message)
reply <email>             Submit a reply from a lead
resolve <email>           Resolve an active escalation as a human reviewer
lead <email>              Full state: qualification, status, event log, email thread
timeline <email>          Chronological event timeline
leads                     All leads with status summary
outbox                    All sent emails with full content
events                    System-wide event log
interventions             Leads needing human attention (Escalated + ApprovedLlmFailed)
links                     All booking links grouped by terminal status type
```

### Configuration

```kotlin
QualificationConfig(
    minTeamSize             = 10,    // below this → Disqualified
    requireUseCase          = true,  // must describe a concrete problem
    requireCommercialIntent = true,  // must express active buying intent
    maxFollowUps            = 3,     // hard cap on follow-up emails
    maxEmailDraftRetries    = 3      // max writer↔reviewer revision cycles
)
```
