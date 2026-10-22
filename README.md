# support-triage

[![CI](https://github.com/wsqreis/support-triage/actions/workflows/ci.yml/badge.svg)](https://github.com/wsqreis/support-triage/actions/workflows/ci.yml)

Event-driven support ticket triage for **Noteflow**, a fictional note-taking app. Two Spring Boot services talk over Kafka. An LLM classifies each ticket and drafts a reply from the help center, and a human approves every reply before it reaches a customer.

The point of the project is the engineering around the model: what happens when Kafka is down, when the LLM is slow or unreachable, when a message is malformed, when the help center has no answer, or when the customer writes in Portuguese.

**Stack:** Java 21 · Spring Boot 4.1 · Spring AI 2.0 · Spring Kafka · PostgreSQL 17 + pgvector · Apache Kafka 4.2 (KRaft) · Resilience4j · Testcontainers 2 · ArchUnit

## Architecture

```
                        ┌──────────────────── ticket-service :8081 ────────────────────┐
 customer ──POST──────▶ │ REST API ─▶ Ticket (JPA) ─┬─▶ tickets   (PostgreSQL)          │
 agent ◀──console/API── │                           └─▶ outbox ─▶ relay ──┐            │
                        └──────────────────────────────────────────────────┼───────────┘
                                 ▲ tickets.triaged                          │ tickets.created
                                 │                     Kafka                ▼
                        ┌────────┴──────────────── triage-service :8082 ──────────────────┐
                        │ classify: LLM (structured output) ─ timeout ─ circuit breaker    │
                        │           └─ fallback: keyword rules                             │
                        │ draft:    pgvector retrieval ─▶ LLM reply ─▶ grounding checks     │
                        └──────────────────────────────────────────────────────────────────┘
```

1. A customer opens a ticket. **ticket-service** stores it and writes a `TicketCreated` event to an **outbox table in the same transaction**. A relay publishes outbox rows to Kafka and marks them published only after the broker acknowledges.
2. **triage-service** classifies the ticket (category, priority, language, sentiment, English summary) and, if the help center covers it, drafts a reply with retrieval-augmented generation. It publishes `TicketTriaged`.
3. ticket-service applies the result **idempotently** and puts the ticket in a queue ordered by priority. An agent reviews the draft, edits it if needed, and approves it. Nothing is sent automatically.

## Design decisions

**Tickets never wait on the LLM.** Every model call goes through `LlmGuard`: a per-call timeout plus one circuit breaker for the model. When the model fails, classification falls back to keyword rules. Those tickets are marked `source: RULES`, the console flags them, and they get no draft. After repeated failures the circuit opens, and the model is not called at all for a minute instead of every ticket waiting for a timeout. With no API key, the whole system still runs on rules.

**No event is lost, and none is applied twice.** The outbox makes "save the ticket" and "publish the event" atomic. The relay locks rows with `FOR UPDATE SKIP LOCKED`, so several instances can relay in parallel without publishing the same event twice. Consumers retry 3 times, then send the message to a **dead-letter topic** instead of blocking the partition. Malformed JSON skips the retries. A duplicate `TicketTriaged` (Kafka is at-least-once) leaves the ticket unchanged.

**Drafts must be grounded, or there is no draft.** There are three gates:
- Retrieval must find articles above a similarity threshold.
- The model must say the articles answer the question (`answerable`).
- Citations are filtered to articles that were actually retrieved, and a draft citing none of them is discarded.

A ticket that fails a gate becomes `NEEDS_HUMAN` instead of getting a plausible-sounding answer.

**Cross-lingual retrieval at no extra cost.** Embeddings run locally (all-MiniLM-L6-v2 via ONNX, no API cost), but that model only understands English: Portuguese tickets scored far below the threshold even for the right article. The classifier already writes a one-sentence English summary, so retrieval searches with that summary. The draft is still written in the customer's language.

**The knowledge base is ready before the first ticket.** Help-center articles are embedded during startup, before the Kafka listeners start. A content hash per article means a restart only re-embeds articles that changed.

**Each service owns its data.** ticket-service owns the `tickets` database. triage-service owns `triage` (the vector store) and keeps no other state, so it needs no outbox: if publishing fails, the incoming message is retried. Events are plain JSON without Java type headers, and the shared `contracts` module holds only records and enums.

## Testing

```bash
./mvnw verify      # unit tests + Testcontainers integration tests (Docker required)
```

- **Unit:** domain rules (state transitions, idempotent triage), outbox relay ordering when Kafka fails, the rules classifier, and the resilience behaviour (fallback on errors, on timeouts and on incomplete model output; the circuit opening after 4 failures). Also the drafting gates, with a scripted chat model.
- **Integration (Testcontainers):** ticket-service over HTTP against real Postgres and Kafka (full lifecycle, duplicate events, queue order, validation). triage-service against real Kafka and pgvector (ticket in, triage out, malformed message to the DLT, incremental knowledge-base sync). Only the LLM and the embedding model are fakes.
- **Architecture (ArchUnit):** web and messaging only reach repositories through the application layer; the domain has no Spring or Kafka dependencies; classification and drafting know nothing about Kafka; and the unguarded LLM classifier is only reachable through the resilient wrapper.

### Classifier evaluation

`ClassifierEval` runs 30 hand-labelled tickets (English, Portuguese, Spanish; account, billing, sync, bugs, feature requests, spam) through the LLM and through the rules baseline. It reports category accuracy, exact and within-one-level priority accuracy, recall on urgent tickets, and language detection.

```bash
OPENROUTER_API_KEY=... ./mvnw -pl triage-service -am test -Dtest=ClassifierEval -Dsurefire.failIfNoSpecifiedTests=false
```

Without a key it evaluates the rules only. It is not part of `verify`, because it calls the real model once per ticket.

## Run it

Requires Java 21 and Docker.

```bash
docker compose up -d                       # PostgreSQL + pgvector, Kafka
./mvnw -DskipTests package
java -jar ticket-service/target/ticket-service-0.1.0.jar &
OPENROUTER_API_KEY=... java -jar triage-service/target/triage-service-0.1.0.jar &
```

Open **http://localhost:8081**: the agent console has the priority queue, the triage labels, the editable draft with its sources, and a form to play the customer. On first start, triage-service downloads the ONNX embedding model (~90 MB) into `~/.cache/support-triage`.

| Variable | Default | |
|---|---|---|
| `OPENROUTER_API_KEY` | – | Without it, triage runs on rules only |
| `LLM_MODEL` | `nvidia/nemotron-3-ultra-550b-a55b:free` | Any OpenRouter model id |
| `LLM_BASE_URL` | `https://openrouter.ai/api/v1` | Any OpenAI-compatible endpoint |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `TICKETS_DB_URL` / `TRIAGE_DB_URL` | `jdbc:postgresql://localhost:5432/tickets` / `.../triage` | |
| `DB_USER` / `DB_PASSWORD` | `triage` / `triage` | |

### API (ticket-service)

| | |
|---|---|
| `POST /api/tickets` | `{customerEmail, subject, body}` → 201 |
| `GET /api/tickets/{id}` | Ticket with triage labels, draft and citations |
| `GET /api/tickets/queue` | Triaged tickets, most urgent first |
| `POST /api/tickets/{id}/resolve` | `{reply}` to send an edited reply, or `{}` to approve the draft. 409 if already resolved or never triaged |

## Project layout

```
contracts/          event records and topic names shared by both services
ticket-service/     REST API, JPA + Flyway, outbox, Kafka listener, agent console
triage-service/     LLM + rules classification, LlmGuard, RAG drafting, knowledge base
  src/main/resources/knowledge/   the help-center articles (Markdown)
compose.yaml        local PostgreSQL + pgvector and Kafka
```

## Limits

- One model at a time: a second provider as a fallback would sit behind the same `LlmGuard`.
- The rules classifier knows English, Portuguese and Spanish keywords. It is meant as a safety net and a baseline, not a competitor.
- The API has no authentication; in production it would sit behind the company's SSO.
- Only infrastructure is containerised. The services can be built into images with `./mvnw spring-boot:build-image`.

## License

[MIT](LICENSE)
