# AI SDR Agent: A Technical Deep Dive

A monday.com RevAI Engineering Challenge

---

## The Problem: Leaky Lead Funnels

Inbound lead qualification is often:
- **Slow:** Manual review takes time.
- **Inconsistent:** Different sales reps qualify differently.
- **Leaky:** Leads are dropped due to high volume or human error.

**Our Solution:** An autonomous, multi-agent Sales Development Representative that qualifies or disqualifies inbound leads through personalised email conversations.

---

## Core Guarantees

- **Zero Lead Leakage:** Every lead is tracked to a terminal state.
- **Hard Guardrails:** Business rules are enforced in code, not prompts. The LLM cannot override them.
- **Graceful Failure Handling:** The system is resilient to LLM failures and network issues.

---

## Architecture: MVI & Unidirectional Data Flow

The system is built on the **Model-View-Intent (MVI)** pattern, ensuring a clean separation of concerns.

- **View:** A simple adapter (CLI, REST API, etc.) that sends user intents.
- **Intent:** Translates raw input into typed actions.
- **Model (Repository):** The single source of truth. Owns all data and state mutations.
- **Orchestrator:** A stateless coordinator that runs the agent pipeline.

**Key Principle:** The business logic has zero knowledge of the outside world.

---

## Why MVI for an AI System?

- **Predictable State:** The LLM produces text; Kotlin decides what it means. State changes are explicit, named `Actions` (e.g., `UpdateQualificationAction`).
- **Testability:** Each layer can be tested in isolation.
- **Flexibility:** Swap the CLI for a webhook receiver without changing any business logic.

---

## Statelessness: The Correctness Guarantee

The **Orchestrator** and all 12 **Sub-Agents** are **completely stateless**.

- A fresh instance is created for every single request.
- No data is carried over between requests.
- This eliminates entire classes of bugs like cross-lead context pollution, memory leaks, and concurrency issues.

---

## The Multi-Agent Pipeline

12 specialised sub-agents, each with a single responsibility.

**New Lead Flow:**
1.  `spam-detector`
2.  `qualification-extractor`
3.  `escalation-detector`
4.  `initial-intent-checker`
5.  `info-sufficiency`
6.  `deal-decision` OR `outreach-writer`

**Reply Flow:**
1.  `qualification-extractor`
2.  `escalation-detector`
3.  `lead-readiness`
4.  `info-sufficiency`
5.  `deal-decision` OR `followup-writer`

---

## Agent Specialization (Examples)

| Agent | Responsibility | Why it's separate |
|---|---|---|
| `spam-detector` | Is this a real lead or junk? | Prevents wasting tokens on spam. |
| `qualification-extractor` | Extracts `use_case`, `team_size`, etc. | Saves data even if the pipeline stops early. |
| `escalation-detector` | Does the email mention pricing/legal? | A hard rule that the LLM cannot bypass. |
| `deal-decision` | Qualify or Disqualify the lead. | The *only* agent that can create a booking link. |
| `email-reviewer` | Is the draft email high quality? | Strict quality gate to prevent generic or bad emails. |

---

## Guardrails & Human-in-the-Loop

Business policies are enforced in Kotlin, not in prompts.

**Hard Policies:**
- Max 3 follow-up emails.
- Booking links can only be created if a lead is fully qualified.
- Any mention of "pricing" or "discount" immediately escalates to a human.

**Human Intent Detection:**
- When a human resolves an escalation, the system interprets their intent to decide the next step (e.g., continue qualifying vs. close the deal).

---

## Resilience: Built for Failure

LLM failures are treated as a core operational concern.

- **Layer 1: Per-Request Retries:** Exponential backoff for transient errors.
- **Layer 2: Agent Cleanup:** `finally { clearHistory() }` ensures agents are always reset.
- **Layer 3: Client Pool:** Automatically marks dead API keys and fails over to the next one.
- **Layer 4: Fallback:** If all LLM clients fail, a fallback booking link and a hardcoded email are sent. **No lead is ever dropped.**

---

## Observability: Know Everything

Every action is a structured event, attached to the lead's history.

- `LeadReceived`
- `EmailSent`
- `QualificationUpdated`
- `HumanEscalationTriggered`
- `BookingLinkCreated`
- `LeadDisqualified`

A full suite of CLI commands allows for deep inspection of any lead, event, or email.

---

## Scaling to Production

The architecture is designed for scale by swapping components.

| Component | Development | Production |
|---|---|---|
| Lead Storage | In-memory Map | PostgreSQL |
| Concurrency | Kotlin `Mutex` | DB Advisory Locks |
| Email Sending | Mock Sender | SendGrid / SES |
| Trigger Source | CLI | Kafka / Webhooks |

The core agent pipeline remains **unchanged**.

---

## Future Improvements

- **Product Context:** Inject knowledge of the product into writer prompts.
- **Idempotency:** Prevent duplicate emails on retries.
- **Webhook Receiver:** Make the agent truly event-driven.
- **Tiered LLM Models:** Use cheaper/faster models for simpler tasks.
- **Structured LLM Output:** Use JSON Schema for reliable data extraction.

---

## Getting Started

1.  **Prerequisites:** JDK 17+, Maven 3.8+, Google Gemini API key.
2.  **Set Environment Variable:**
    ```bash
    export GOOGLE_API_KEY=your_key_here
    ```
3.  **Run:**
    ```bash
    mvn compile exec:java -Dexec.mainClass=org.example.MainKt
    ```

---

## Questions?
