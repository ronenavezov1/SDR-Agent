# AI SDR Agent — RevAI Engineering Challenge

An AI-powered Sales Development Representative that converts inbound leads into qualified sales opportunities — or intelligently disqualifies them — through email conversation.

---

## Quick Start

```bash
export GOOGLE_API_KEY=your_key_here
./mvnw exec:java   # or use IntelliJ → run MainKt
```

Then type `demo` to run the built-in 3-scenario walkthrough, or `help` to see all commands.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  SdrConsoleView  (CLI: new-lead / reply / resolve / demo)       │
└──────────────────────────┬──────────────────────────────────────┘
                           │ processNewLead / processReply / resolveEscalation
┌──────────────────────────▼──────────────────────────────────────┐
│  SdrOrchestrator                                                │
│  ├─ LeadStore        (authoritative lead state)                 │
│  ├─ AuditLog         (append-only event log)                    │
│  ├─ MockEmailService                                            │
│  ├─ MockBookingService                                          │
│  └─ agentsByLead: Map<leadId → AiAgent>                         │
└──────────────────────────┬──────────────────────────────────────┘
                           │ one AiAgent per lead
┌──────────────────────────▼──────────────────────────────────────┐
│  AiAgent  (ReAct loop — from existing infrastructure)           │
│  ├─ Tools (read-only):   getLeadState, checkQualification       │
│  └─ Actions (writes):    sendOutreachEmail, sendFollowUpEmail,  │
│                          updateQualification, createBookingLink, │
│                          escalateToHuman, disqualifyLead         │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Gemini 2.5 Flash
┌──────────────────────────▼──────────────────────────────────────┐
│  GeminiLlmClient  (swappable via LlmClient interface)           │
└─────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| `SdrOrchestrator` | Lead lifecycle, policy pre-checks, routing to per-lead agents |
| `AiAgent` (reused) | ReAct reasoning loop; owns per-lead conversation history |
| `LeadStore` | Single source of truth for lead state (not inside the agent) |
| `AuditLog` | Append-only, structured event log for observability |
| Tools/Actions | The only write path into `LeadStore` — policy checked in code |
| `QualificationConfig` | All qualification rules in one configurable object |
| Mock services | `MockEmailService`, `MockBookingService` — swap for real integrations |

---

## Key Design Decisions & Tradeoffs

### 1. Lead state lives outside the agent

**Decision:** The `Lead` object (qualification data, status, email thread) lives in `LeadStore`, not inside the agent's conversation history.

**Why:** Agent histories are conversation artifacts — they can be summarised, truncated, or replayed. Lead state is durable business data. Separating them means:
- History compression (via `AiAgent.summarizeHistory`) never loses durable lead data.
- Multiple agents (or a recovered agent after failure) can pick up the same lead seamlessly.
- State transitions are the only path to mutate lead data — every change is visible in the audit log.

**Tradeoff:** The agent must read lead state via a tool call (`getLeadState`) rather than "just knowing" it from history. This costs one extra LLM turn but pays for reliability and observability.

### 2. One agent per lead

**Decision:** `SdrOrchestrator` creates a separate `AiAgent` instance for each lead and stores it in `agentsByLead`.

**Why:** Each agent's history is the reasoning trail for *that specific lead*. No cross-lead contamination, and the full reasoning chain is preserved for audit/debugging. The orchestrator is the parent that coordinates and inspects agent state.

**Tradeoff:** Memory scales linearly with concurrent leads. For thousands of concurrent long-running leads, agent history would need periodic summarisation (already supported via `AiAgent.summarizeHistory`) or externalisation to a database.

### 3. Policies enforced in code, not only in prompts

**Decision:** Every business policy is a hard check in tool/action code — not just a prompt instruction.

| Policy | Where enforced |
|---|---|
| No duplicate outreach | `SendOutreachEmailAction` checks `lead.outreachSent` |
| Max follow-ups | `SendFollowUpEmailAction` checks `lead.followUpCount >= config.maxFollowUps` |
| Booking requires qualification | `CreateBookingLinkAction` re-validates all criteria |
| No email while escalated | `SendFollowUpEmailAction` checks `lead.status == ESCALATED_HUMAN` |

Prompt instructions tell the model *when* to use each tool. Code enforces that even if the model calls the wrong tool at the wrong time, the policy holds. Defense in depth.

**Tradeoff:** Slightly more code to maintain — but this is non-negotiable for a system that moves real leads to qualified/disqualified states.

### 4. Human-in-the-loop as a blocking state transition

**Decision:** Pricing escalation is modelled as a `LeadStatus.ESCALATED_HUMAN` state. The orchestrator refuses to route any input to the agent while a lead is in this state.

**Why:** This guarantees the agent cannot "drift" around the escalation. The pricing keywords are detected both in the orchestrator (before the model sees them) and in the system prompt (so the model understands *why* it must escalate). `EscalateToHumanAction` also sets the status — making the guard a tri-layer defence.

### 5. CQRS split for tools

**Decision:** Read-only capabilities are `Tool` (execute); state-mutating ones are `Action` (perform). The `AgentConfig` holds them in separate maps.

**Why:** This makes the distinction explicit in the type system. Read-only tools can safely be called in parallel, cached, or retried; actions must be idempotent by design and never called twice for the same effect (e.g. `sendOutreachEmail` is guarded by `outreachSent`).

### 6. Qualification rules in a single config object

**Decision:** All thresholds (`minTeamSize`, `maxFollowUps`, `requireUseCase`, `pricingKeywords`) live in `QualificationConfig`, injected into the orchestrator.

**Why:** Business rules change. A new customer segment might have a different minimum team size. Moving thresholds into a data object means the system prompt and tool checks both read from the same source — no drift between what the model is told and what the code enforces.

---

## Observability

Every meaningful event is logged to `AuditLog`:

```
🆕 [AUDIT 11:15:01] [lead:demo-001] LEAD_RECEIVED  {name=Sarah Chen, company=TechCorp}
📧 [AUDIT 11:15:04] [lead:demo-001] EMAIL_SENT      {type=OUTREACH, to=sarah@techcorp.com}
📨 [AUDIT 11:15:10] [lead:demo-001] REPLY_RECEIVED  {preview=Thanks for reaching out...}
🔍 [AUDIT 11:15:12] [lead:demo-001] QUALIFICATION_UPDATED  {teamSize=50, useCase=sprint planning}
✅ [AUDIT 11:15:13] [lead:demo-001] LEAD_QUALIFIED  {name=Sarah Chen, link=https://booking...}
```

Use `timeline <leadId>` in the CLI for a per-lead view, or `timeline` for all leads.

The `AiAgent.history` provides the full reasoning trace (LLM reasoning, tool calls, tool results) per lead, readable at any time.

---

## Human-in-the-Loop Flow

```
Lead reply contains "pricing" / "discount"
  │
  ▼
SdrOrchestrator detects pricing keywords (pre-check)
  → adds ⚠️ warning to agent input
  │
  ▼
AiAgent system prompt: MUST call escalateToHuman
  │
  ▼
EscalateToHumanAction sets status = ESCALATED_HUMAN
  → prints escalation banner to console
  → blocks all further agent processing for this lead
  │
  ▼
Human types: resolve <leadId>
  → SdrOrchestrator sets status = QUALIFYING
  → calls agent.processInput("Human responded: ...")
  │
  ▼
Agent continues qualification with human guidance
```

---

## Qualification Logic

Configurable via `QualificationConfig`:

```kotlin
QualificationConfig(
    minTeamSize            = 10,     // team_size must be ≥ this
    requireUseCase         = true,   // use_case must be described
    requireCommercialIntent = true,  // must be actively buying/evaluating
    maxFollowUps           = 3,      // max emails before giving up
    pricingKeywords        = listOf("price", "pricing", "cost", "discount", ...)
)
```

`CheckQualificationTool` evaluates all three and returns:
- `QUALIFIED` → agent calls `createBookingLink`
- `NEEDS_MORE_INFO` → agent calls `sendFollowUpEmail`
- `DISQUALIFIED` → agent calls `disqualifyLead`

---

## What I Would Improve Next

1. **Persistent storage** — swap `LeadStore` for a database (Postgres/SQLite). The interface is already isolated; it's a one-file change.

2. **Agent history persistence** — serialise `AgentHistory` to the DB so agents survive process restarts and can resume long-running leads.

3. **Retry / idempotency** — add idempotency keys to email sends so a crash-and-replay scenario never sends the same email twice.

4. **Richer qualification extraction** — add a dedicated structured extraction step (JSON-schema tool call) instead of relying on the LLM to call `updateQualification` with correct field types.

5. **Evaluation harness** — record real LLM decisions against labelled test leads to detect prompt regressions when the model or prompt changes.

6. **Async email inbox polling** — replace the CLI `reply` command with a real inbox webhook / polling loop, completing the feedback cycle without human simulation.

---

## Open Design Question

### How do architectural choices help the agent reliably reach the business objective over time?

The core reliability challenge for a long-running business agent is **drift** — the gap that grows between what the system *intends* to do and what it *actually* does as state accumulates, failures occur, and the model reasons imperfectly.

This system addresses drift at four levels:

**1. Externalised, authoritative state.** Lead qualification data lives in `LeadStore`, not inside the agent's chat history. This means agent reasoning errors (hallucinating a team size, forgetting a prior reply) cannot corrupt durable business data — they can only affect the *next* tool call. If the model calls `updateQualification` with wrong data, the wrong data is stored and visible in the audit log, recoverable by a human. The agent's history can be compressed, replayed, or cleared without affecting the lead's qualification record.

**2. Policy enforcement in code, not in prompts.** Prompts set intent; code enforces invariants. A prompt that says "never send more than 3 follow-ups" will eventually be violated — by model drift, context truncation, or a jailbreak. A tool that checks `followUpCount >= maxFollowUps` and returns `POLICY_BLOCKED` will not. Every business-critical boundary (qualification gates, escalation gates, deduplication) is enforced in tool code, making policy violation structurally impossible rather than merely unlikely.

**3. Human escalation as a state machine gate.** Pricing discussions are not "handled carefully" — they are *blocked* until a human resolves them. The orchestrator refuses to route agent input for ESCALATED_HUMAN leads, and mutating actions refuse to execute on escalated leads. The human's approval is a first-class state transition, not an afterthought. This maps directly to the business objective: a missed pricing escalation could commit the company to a discount it never approved.

**4. Append-only audit log as the ground truth.** The audit log is never modified, only appended to. Every state change is logged with a timestamp and details before it takes effect. This means that even when the agent makes a wrong decision (e.g. disqualifies a borderline lead), the full decision trail is available for review, correction, and future prompt improvement. Observability is not a debugging tool — it's how the business maintains trust in the agent's outputs over time.

The business objective is not "send emails" — it is to reliably convert good leads and discard bad ones at scale, with human oversight where it matters. Each architectural choice maps to that objective: externalised state enables recovery, policy code prevents violations, escalation gates protect sensitive decisions, and the audit log makes everything visible.

---

## Scaling Question

To support thousands of long-running leads in production I would move from an in-process, in-memory design to a durable, event-driven one: leads and their qualification state would be persisted in a relational database (PostgreSQL), and the per-lead `AiAgent` instances would be ephemeral workers spun up from that stored state rather than kept alive in a map. Each incoming event (new lead, reply, escalation resolved) would be written to an event queue (Kafka or SQS), consumed by a stateless worker pool that loads the relevant lead from the DB, replays the last N events into a freshly constructed agent, runs one reasoning step, and writes the result back. Agent history would be periodically summarised and stored as a compressed checkpoint so cold-starts stay cheap. The audit log would be a time-series table with indexes on `leadId` and `eventType`, feeding a real-time dashboard. Human escalation queues would be surfaced via a lightweight internal web UI rather than a CLI. The `QualificationConfig` would be loaded from a config service so thresholds can be changed without redeployment, and email throughput would be rate-limited per domain to avoid spam flags.
