# AI SDR Agent — RevAI Engineering Challenge

> An AI-powered Sales Development Representative that qualifies or intelligently disqualifies inbound leads through automated email conversations, with human-in-the-loop escalation and configurable business policies.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Getting Started](#2-getting-started)
3. [Architecture & Data Flow](#3-architecture--data-flow)
4. [State & Reliability](#4-state--reliability)
5. [Guardrails & Human-in-the-Loop](#5-guardrails--human-in-the-loop)
6. [Scaling to Thousands of Leads](#6-scaling-to-thousands-of-leads)
7. [What Would I Improve Next](#7-what-would-i-improve-next)

---

## 1. Project Overview

The business objective is to automate the top of the sales funnel: an inbound lead arrives, the agent sends a personalised outreach email, extracts qualification signals from replies, and progresses the lead toward one of two terminal outcomes — **Qualified** (receives a booking link) or **Disqualified** (receives a polite farewell).

The system enforces a strict qualification model:

| Signal | Rule |
|---|---|
| `team_size` | Must be ≥ configured minimum (default: 10) |
| `use_case` | Must be explicitly and clearly described |
| `commercial_intent` | Must be confirmed (active evaluation / budget intent) |

All three criteria are **configurable** at startup via `QualificationConfig` — no code changes required to adjust thresholds.

The agent is driven by an **interactive CLI** that simulates a real inbox: submit new leads, process replies, resolve escalations, and inspect the full system event log at any point.

---

## 2. Getting Started

### Prerequisites

- JDK 17+ (JBR 25 recommended)
- Maven 3.9+
- A valid **Google Gemini API key** (`gemini-2.5-flash` model)

### Run

```bash
export GOOGLE_API_KEY=your_key_here

# Build
mvn compile

# Run
mvn exec:java -Dexec.mainClass="org.example.MainKt"
```

### CLI Commands

```
╔═══════════════════════════════════════════════════════╗
║   new-lead          Submit a new inbound lead         ║
║   reply  <email>    Simulate a lead reply             ║
║   resolve <email>   Provide human escalation response ║
║   leads             List all leads with status        ║
║   lead   <email>    Show lead detail                  ║
║   timeline [email]  Show per-lead event timeline      ║
║   events            Show global system event log      ║
║   demo              Run automated 3-scenario demo     ║
╚═══════════════════════════════════════════════════════╝
```

### Demo Mode

The fastest way to see the full system in action is the `demo` command. It automatically executes three realistic scenarios end-to-end:

| Scenario | Description |
|---|---|
| **1 — Happy Path** | A well-qualified lead (50-person team, clear use case, confirmed budget) → Booking link issued |
| **2 — Pricing Escalation** | Lead asks about discounts → Agent escalates to human, waits for input, then resumes |
| **3 — Disqualification** | Solo freelancer reveals team size of 1 → Agent politely disqualifies |

```
sdr> demo
```

---

## 3. Architecture & Data Flow

The system enforces a **strict unidirectional (top-down) dependency graph**. No layer ever depends on the layer above it.

```
┌─────────────────────────────────────────────────────┐
│  SdrConsoleView   (I/O only — stdin/stdout)         │
└────────────────────────┬────────────────────────────┘
                         │ delegates all logic
┌────────────────────────▼────────────────────────────┐
│  SdrViewModel     (Routing & formatting)            │
│  Commands ──► SdrOrchestrator                       │
│  Reads    ──► SdrRepository                         │
└──────┬──────────────────────────┬───────────────────┘
       │ commands                 │ reads
┌──────▼───────────────┐   ┌──────▼───────────────────┐
│  SdrOrchestrator     │   │  SdrRepository            │
│  (Stateless service) │──►│  (Single source of truth) │
└──────┬───────────────┘   └──────────────────────────┘
       │ processInput()
┌──────▼───────────────────────────────────────────────┐
│  AiAgent × 6  (Short-lived ReAct workers)            │
│  Actions × 6  (State mutations + policy enforcement) │
│  Tools   × 2  (Read-only repository queries)         │
└──────────────────────────────────────────────────────┘
```

### Component Breakdown

#### SdrConsoleView & SdrViewModel — Presentation Layer

`SdrConsoleView` is deliberately thin: it reads stdin, calls `SdrViewModel`, and prints the returned `String`. Zero business logic lives here.

`SdrViewModel` owns all presentation logic: it routes mutating commands to the `SdrOrchestrator` and queries the `SdrRepository` for display data. The separation means the CLI could be swapped for a REST controller or a web UI by replacing only the View — the rest of the stack is untouched.

#### SdrOrchestrator — Stateless Service

The orchestrator holds **no persistent state of its own**. Every method receives the information it needs from the repository at call time. Its sole responsibility is to coordinate the six specialised agents in the correct sequence.

Each call follows a deterministic pipeline:

```
processNewLead:   outreachWriter

processReply:     escalationDetector
                       │ SAFE ──► qualificationExtractor
                                       │
                                  infoSufficiency
                                  ┌────┴─────────────┐
                          DECIDE_NOW /          NEEDS_MORE_INFO
                          DISQUALIFY_NOW
                               │                     │
                          dealDecision          followUpWriter
```

#### AiAgent — Short-Lived ReAct Worker

Each `AiAgent` runs a standard **ReAct (Reason + Act) loop**: it sends the conversation history to the LLM, executes any requested tool calls concurrently, appends results to history, and repeats until the LLM emits a final text response.

**Critical design choice:** the agent **clears its own memory immediately after returning a result**. This is an intentional constraint: since all durable state lives in `SdrRepository`, an agent that retains cross-turn history would be carrying stale or conflicting context. Clearing after each call prevents context pollution and eliminates a class of LLM hallucination (e.g., "I already sent this email" when the new call is for a different lead). Each invocation is fully self-contained.

The six specialised agents and their responsibilities:

| Agent | Tools | Actions | Purpose |
|---|---|---|---|
| `outreachWriter` | `getLeadState` | `sendOutreachEmail` | Compose and send the first personalised email |
| `followUpWriter` | `getLeadState` | `sendFollowUpEmail` | Send a targeted follow-up for the single most important missing field |
| `escalationDetector` | `getLeadState` | `escalateToHuman` | Scan incoming messages for pricing / sensitive triggers → `SAFE` or `ESCALATED` |
| `qualificationExtractor` | `getLeadState` | `updateQualification` | Extract `useCase`, `teamSize`, `commercialIntent` — only if clearly and explicitly stated |
| `infoSufficiency` | `getLeadState`, `checkQualification` | _(none)_ | Assess whether data is sufficient vs. remaining follow-up budget → `DECIDE_NOW / NEEDS_MORE_INFO / DISQUALIFY_NOW` |
| `dealDecision` | `getLeadState`, `checkQualification` | `createBookingLink`, `disqualifyLead` | Make and execute the final qualified / disqualified outcome |

Each agent receives only the tools and actions relevant to its role. An `outreachWriter` cannot accidentally disqualify a lead; a `dealDecision` agent cannot send follow-up emails. This **principle of least privilege** at the agent level is a meaningful guardrail.

#### Actions & Tools — Execution Layer

**Tools** (`Tool` interface) are **read-only**: they query the repository and return a string for the LLM to reason about. They never mutate state.

**Actions** (`Action` interface) are **write-only**: they mutate the repository and log a domain event. Policies are enforced *inside* the action code before any mutation occurs — the LLM cannot bypass a policy by wording a prompt differently.

| Capability | Type | Policies enforced |
|---|---|---|
| `sendOutreachEmail` | Action | `NO_DUPLICATE_OUTREACH` |
| `sendFollowUpEmail` | Action | `MAX_FOLLOW_UPS`, `NO_EMAIL_ESCALATED` |
| `createBookingLink` | Action | `BOOKING_REQUIRES_QUALIFICATION` |
| `escalateToHuman` | Action | Idempotent (cannot double-escalate) |
| `disqualifyLead` | Action | Idempotent (cannot re-disqualify) |
| `updateQualification` | Action | _(none — pure data write)_ |
| `getLeadState` | Tool | _(read-only)_ |
| `checkQualification` | Tool | _(read-only)_ |

#### SdrRepository — Single Source of Truth

The repository depends on **nothing** — only plain data classes. It holds:

- `_leads: Map<String, Lead>` — the current state of every lead, keyed by email
- `_systemEvents: List<Event>` — an immutable, append-only global event log

Every domain event carries a `leadEmail` field, so the global log can be filtered per-lead or inspected as a full system timeline. This design mirrors **event sourcing**: state can always be reconstructed by replaying events.

---

## 4. State & Reliability

### What state is kept and why

| Stored on `Lead` | Purpose |
|---|---|
| `status: LeadStatus` | Current lifecycle position (sealed interface: `New`, `AwaitingClientResponse`, `Escalated`, `Qualified`, `Disqualified`) |
| `useCase`, `teamSize`, `commercialIntent` | Incrementally populated qualification fields |
| `emailThread` | Full email history (OUTBOUND / INBOUND), used as conversation context |
| `followUpCount`, `outreachSent`, `bookingLink` | Policy enforcement counters and outcome markers |
| `events: List<Event>` | Per-lead audit trail — every action taken is recorded |

**What is deliberately not stored:** agent conversation history. Once an `AiAgent` returns a result it clears its `_history`. The LLM's "memory" of a lead is reconstructed on each call from `lead.toContextString()` and `lead.events` — structured, deterministic data rather than fragile free-text conversation logs.

### Avoiding unsafe mutations

The LLM **cannot directly mutate any state**. The only path to state change is:

```
LLM requests function call
  → AiAgent.executeCapabilitySafely()
    → Action.perform()
      → Policy checks (may reject)
        → Lead field update
          → repository.logEvent(Event.*)
```

This means every mutation is:
1. **Intentional** — the LLM must explicitly request a named action
2. **Validated** — policies run in Kotlin code before the mutation
3. **Audited** — a typed domain event is appended to both the lead's log and the global event log

There is no "catch-all" mutation surface. The LLM cannot call `lead.status = X` or skip the event log.

### LeadStatus as a sealed interface

`LeadStatus` is a **sealed interface** rather than an enum. `Escalated` is a `data class` carrying the full `HumanEscalation` payload (reason, trigger message, human response). This co-locates the state and its data, preventing a nullable `escalation: HumanEscalation?` field from drifting out of sync with the status field.

```kotlin
sealed interface LeadStatus {
    data object New                    : LeadStatus
    data object AwaitingClientResponse : LeadStatus
    data object Qualified              : LeadStatus
    data object Disqualified           : LeadStatus
    data class  Escalated(val escalation: HumanEscalation) : LeadStatus
}
```

---

## 5. Guardrails & Human-in-the-Loop

### Human-in-the-Loop: Pricing Escalation

When a lead's reply contains any configured pricing keyword (`price`, `discount`, `cost`, `budget`, etc.), two independent layers enforce escalation:

1. **Prompt layer:** The system prompt instructs every reply-processing agent to call `escalateToHuman` immediately upon detecting a pricing mention.
2. **Code layer:** The orchestrator scans `replyText` for `pricingKeywords` before calling any agent. If a match is found, the `⚠️ PRICING KEYWORDS DETECTED` warning is injected directly into the agent's prompt, eliminating any ambiguity.
3. **Action layer:** `EscalateToHumanAction` sets `lead.status = LeadStatus.Escalated(...)`. All subsequent actions that try to email or qualify the lead check for this status and reject the request with a `POLICY_BLOCKED` event.

The agent **cannot resume** processing the lead until a human provides a response via `resolve <email>`, at which point the orchestrator resumes the qualification pipeline using the human's guidance as context.

### Policy Guardrails (Enforced in Kotlin, Not in Prompts)

| Policy | Enforcement point | Behaviour |
|---|---|---|
| `NO_DUPLICATE_OUTREACH` | `SendOutreachEmailAction` | Rejects if `lead.outreachSent == true` |
| `MAX_FOLLOW_UPS` | `SendFollowUpEmailAction` | Rejects if `followUpCount >= config.maxFollowUps` |
| `NO_EMAIL_ESCALATED` | `SendFollowUpEmailAction` | Rejects while `status is LeadStatus.Escalated` |
| `BOOKING_REQUIRES_QUALIFICATION` | `CreateBookingLinkAction` | Rejects if any qualification criterion is unmet |

All policy violations are recorded as `Event.PolicyBlocked(leadEmail, policyName, reason)` events — they are visible in the event log and do not silently fail.

---

## 6. Scaling to Thousands of Leads

> *"If this had to support thousands of long-running leads in production, what would you change?"*

The architecture was designed with this question in mind. Because `SdrOrchestrator` is **already completely stateless** and `SdrRepository` is the only stateful component, horizontal scaling is primarily an infrastructure problem, not an application redesign.

**1. Persistent Repository**

Replace the in-memory `Map<String, Lead>` with a persistent database. The event log (`_systemEvents`) maps naturally to an **append-only events table** in PostgreSQL, with the current lead state either derived by replaying events or maintained as a materialised view. The `SdrRepository` interface requires no changes — only its implementation.

**2. Asynchronous Processing via Message Queue**

The current model is synchronous: the CLI blocks while the LLM runs. At scale, incoming emails (webhook callbacks from a real email provider) would be pushed onto a **message queue (Kafka / RabbitMQ)**. Each message contains `{ leadEmail, replyText }`. A pool of stateless worker processes consumes the queue, each loading the lead from the DB, running the orchestrator pipeline, and writing the result back — without any shared in-process state.

**3. LLM Call Concurrency**

Leads are independent. With a message queue, thousands of leads can be processed concurrently by multiple worker instances. Since each `SdrOrchestrator` invocation is a pure function over `(Lead, replyText)` → `String`, no cross-lead locking is required.

**4. Observability at Scale**

The per-lead `Event` log and global `_systemEvents` list map directly to a **structured event stream** (e.g., pushed to Datadog, CloudWatch, or an internal analytics store). At scale, this enables dashboards over lead funnel conversion rates, agent latency, and policy violation frequencies — all without adding instrumentation code.

---

## 7. What Would I Improve Next

| Area | Current state | Next step |
|---|---|---|
| **Email integration** | `MockEmailService` prints to stdout | Replace with a real provider (SendGrid / Postmark) via an injectable `EmailService` interface — the actions require no changes |
| **Entrypoint** | Interactive CLI | Expose a **REST API / Webhook endpoint** so real email providers (Gmail API, Outlook) can POST inbound replies directly; the `SdrViewModel`'s command layer maps cleanly to HTTP handlers |
| **Structured LLM output** | Agents respond with free text; the orchestrator parses keywords (`ESCALATED`, `DECIDE_NOW`) | Enforce **JSON schema responses** via Gemini's structured output / function-calling API for all routing agents, making the pipeline deterministic and testable without LLM calls |
| **Persistence** | In-memory `HashMap` | PostgreSQL + JPA / Exposed — the `SdrRepository` interface is already the only database boundary |
| **Testing** | No automated tests | Unit-test every `Action` and `Tool` with a mock repository; integration-test the orchestrator pipeline with a stubbed `LlmClient` |
| **Multi-tenancy** | Single qualification config | Parameterise `QualificationConfig` per-tenant, stored in DB — the entire agent pipeline is already config-driven |

---

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 2.3.21 |
| Build | Maven 3.9 |
| LLM | Google Gemini 2.5 Flash (`google-genai` SDK 1.60.0) |
| Concurrency | Kotlin Coroutines (`kotlinx-coroutines-core` 1.10.2) |
| Architecture | MVVM + Unidirectional Data Flow |
| Agent pattern | ReAct (Reason + Act) loop |

---

## Project Structure

```
src/main/kotlin/
├── Main.kt                        # Dependency wiring (top-down, no cycles)
├── sdr/
│   ├── Lead.kt                    # Core domain model
│   ├── LeadStatus.kt              # Sealed interface (New/Awaiting/Escalated/Qualified/Disqualified)
│   ├── Event.kt                   # Sealed interface — domain event log (each carries leadEmail)
│   ├── QualificationConfig.kt     # Configurable qualification rules & policies
│   └── HumanEscalation.kt         # Escalation payload (embedded in LeadStatus.Escalated)
├── repositiories/
│   └── SdrRepository.kt           # Pure data layer — leads + global event log
├── orchestrator/
│   └── SdrOrchestrator.kt         # Stateless coordinator of 6 specialised AiAgents
├── agent/
│   ├── AiAgent.kt                 # Generic ReAct loop (clears history after each run)
│   ├── AgentConfig.kt             # Agent configuration & tool/action injection
│   └── AgentHistory.kt            # Typed conversation history entries
├── actions/                       # State-mutating capabilities (enforce policies before writing)
│   ├── SendOutreachEmailAction.kt
│   ├── SendFollowUpEmailAction.kt
│   ├── UpdateQualificationAction.kt
│   ├── CreateBookingLinkAction.kt
│   ├── EscalateToHumanAction.kt
│   └── DisqualifyLeadAction.kt
├── tools/                         # Read-only repository queries
│   ├── GetLeadStateTool.kt
│   └── CheckQualificationTool.kt
├── mock/
│   ├── MockEmailService.kt        # Simulates email delivery (prints to stdout)
│   └── MockBookingService.kt      # Generates mock calendar booking URLs
├── view/
│   └── SdrConsoleView.kt          # Pure I/O — stdin/stdout only
├── viewmodel/
│   └── SdrViewModel.kt            # Routing, formatting, demo scenarios
└── llm/
    ├── LlmClient.kt               # Interface (swap Gemini for any provider)
    └── GeminiLlmClient.kt         # Google Gemini 2.5 Flash implementation
```
