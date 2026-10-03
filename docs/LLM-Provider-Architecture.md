# CompanionAI LLM Provider Architecture

Status legend used throughout this document:

| Marker | Meaning |
|---|---|
| `[CURRENT]` | Pre-existing behaviour, read directly from this repository's source before the provider refactor |
| `[IMPLEMENTED]` | Written in this repository, compiling, and covered by tests. **Not** confirmed against real hardware |
| `[PLANNED]` | Design decision taken, not yet implemented |
| `[VALIDATED]` | Verified by measurement on hardware. **Nothing in this document holds this marker yet.** |
| `[TBD]` | Unknown. Must be determined by running the probes in this document or `X1Pro-FastFlowLM-Validation.md` |
| `[VENDOR]` | Stated by upstream vendor documentation. Trusted as a claim, not as a measurement |

`[IMPLEMENTED]` and `[VALIDATED]` are deliberately distinct. An `[IMPLEMENTED]` row is backed by the
build and the test suite; an `[VALIDATED]` row is backed by a measurement on the target hardware.
Nothing in the `llm/` package has reached `[VALIDATED]`.

---

# 1. Purpose

CompanionAI must run unchanged on two very different machines:

| | Development | Production |
|---|---|---|
| Host | Intel PC | Minisforum X1 Pro HX-370 |
| Accelerator | NVIDIA RTX 4090 (24 GB) | AMD XDNA2 NPU |
| RAM | 24 GB+ | 64 GB |
| LLM runtime | Ollama | FastFlowLM |
| Model family | Qwen | Qwen |

The application must not contain hardware- or runtime-specific branching:

```text
if (NVIDIA) ...        // forbidden
if (AMD) ...           // forbidden
if (Ollama) ...        // forbidden outside the provider layer
if (FastFlowLM) ...    // forbidden outside the provider layer
```

The intended shape:

```text
                 CompanionAI
                      |
              LlmProvider (interface)
                      |
        +-------------+-------------+
        |                           |
  OllamaProvider            FastFlowLmProvider
        |                           |
  OpenAiCompatProvider  (shared transport)
        |                           |
   Ollama /v1                 FastFlowLM /v1
        |                           |
   RTX 4090                     XDNA2 NPU
```

[IMPLEMENTED]` Runtime selection and model selection are configuration concerns. The same application artefact serves both hosts without source changes.

---

# 2. `[CURRENT]` The existing LLM contract

`[CURRENT]` **Before the refactor there was no provider abstraction.** The entire LLM surface was a single
class, `LlmClient`, hard-wired to Ollama's **native** (non-OpenAI) API. That class has been deleted.
This section is kept as the record of *why* each requirement in §3 exists; the line-number citations
that used to point into it are gone, because the file no longer exists.

To inspect the deleted class: `git show 19a9dea:src/main/java/davejones74/campanionai/LlmClient.java`.

## 2.1 `[CURRENT]` The old wire contract

Request:

```jsonc
POST {baseUrl}/api/chat
Content-Type: application/json

{
  "model":    "<model>",
  "stream":   true | false,
  "messages": [ { "role": "system|user|assistant", "content": "..." } ],
  "options":  { "temperature": 0.7, "num_ctx": <integer, optional> }
}
```

Responses:

| Mode | Framing | Content path | Terminator |
|---|---|---|---|
| non-streaming | single JSON object | `root.message.content` | n/a |
| streaming | **NDJSON** — one JSON object per line | `root.message.content` (per line) | `root.done == true` |

Error convention `[CURRENT]`: a `{"error": "..."}` field was treated as a failure **even when the HTTP
status was 200**. Genuine non-200 responses also raised an error.

Timeouts `[CURRENT]`: 10 s connect; 120 s non-streaming request; 300 s streaming request.

Public surface `[CURRENT]`, all of which `LlmProvider` reproduces:

| Member | Purpose | Successor |
|---|---|---|
| `String chat(List<ChatMessage>) throws LlmException` | non-streaming reply text | `LlmProvider.chat` |
| `void chatStream(List<ChatMessage>, ChunkHandler) throws LlmException` | delta callback | `LlmProvider.chatStream` |
| `record ChatMessage(String role, String content)` | message type | `LlmMessage` |
| `@FunctionalInterface ChunkHandler { void onDelta(String delta) }` | stream callback | unchanged shape |
| `static class LlmException extends Exception` | failure type | `LlmException`, still a checked exception so existing `catch` sites are unchanged |

## 2.2 `[IMPLEMENTED]` Ollama coupling points, and what replaced them

| # | Coupling | Resolution |
|---:|---|---|
| 1 | Endpoint path `"/api/chat"` — Ollama-native | `OpenAiCompatTransport` posts to `/v1/chat/completions` |
| 2 | Reply path `message.content` — Ollama-native | `choices[0].message.content`, with NDJSON tolerated for Ollama |
| 3 | `{"error": ...}` at HTTP 200 | Both 2xx-with-error and non-2xx are treated as failures |
| 4 | **NDJSON** stream framing | Transport accepts SSE `data:` frames and bare JSON lines |
| 5 | `options.num_ctx` — per-request context override, Ollama-only | `OllamaProvider` only; `LlmCapability.PER_REQUEST_CONTEXT_LENGTH` gates it |
| 6 | `"Ollama"` in Javadoc, exception and log strings | Vendor naming confined to `LlmProviderFactory` |
| 7 | `campanionai.model` default `qwen3.6:27b` — an Ollama tag | `LlmProviderFactory.DEFAULT_MODEL`, still overridable |
| 8 | `campanionai.ollamaUrl`, default `http://localhost:11434` | `companionai.llm.baseUrl`; legacy key still honoured |
| 9 | `campanionai.numCtx` wired straight into #5 | Routed through the factory, capability-checked |
| 10 | `script/switch-model.sh` shells out to the `ollama` CLI | `[PLANNED]` Phase 5 — provider-neutral model probe |
| 11 | `script/run-*.sh` (9 files) bake in the Ollama-only launch workflow | `[PLANNED]` Phase 5 — `script/run.sh <provider> <model>` |
| 12 | `docs/X1Pro-Home-Hosting-Architecture.md` | Hardcodes Ollama `:11434` as the production LLM |

## 2.3 `[CURRENT]` Ollama-centric configuration

From `README.md:103-115` and `ModelServlet.java:50-57`:

| Property | Default | Ollama-specific? |
|---|---|---|
| `campanionai.model` | `qwen3.6:27b` | Yes — an Ollama tag |
| `campanionai.ollamaUrl` | `http://localhost:11434` | Yes — names the runtime |
| `campanionai.numCtx` | *(unset)* | Yes — maps to `options.num_ctx` |
| `campanionai.temperature` | `0.7` | No — portable |
| `campanionai.maxContextTokens` | `20000` | No — portable |
| `campanionai.historyTokens` | `8000` | No — portable |
| `campanionai.chunkTokens` / `chunkOverlap` | `1500` / `200` | No — portable |
| `campanionai.live.contextTokens` | `4000` | No — portable |

Note `[CURRENT]` `script/run-qwen2.5-1.5b.sh` already establishes the project idiom of shrinking the whole context budget bundle for a small model (`numCtx=2048`, `maxContextTokens=1024`, `historyTokens=512`, `chunkTokens=512`, `chunkOverlap=64`, `live.contextTokens=512`). The planned provider profiles follow this existing pattern rather than inventing a new one.

---

# 3. What CompanionAI actually requires from an LLM

This is the load-bearing section. The required set is **smaller** than an OpenAI client would normally assume, and every entry is justified by a specific call site.

## 3.1 Required features

`Wire-shape tests` are the assertions in `OpenAiCompatTransportTest` that hold against a stub. They
prove the client emits and accepts the shape; they do **not** prove any runtime behaves that way.

| # | Feature | Wire-shape tests | Hardware status |
|---:|---|---|---|
| R1 | `POST /v1/chat/completions` | `[IMPLEMENTED]` posts to this path | `[TBD]` probe A1 / F1 |
| R2 | `messages[].role` ∈ `system`,`user`,`assistant` | `[IMPLEMENTED]` | `[TBD]` A1 / F1 |
| R3 | Top-level `temperature` | `[IMPLEMENTED]` | `[TBD]` A1 / F1 |
| R4 | Non-stream reply at **`choices[0].message.content`** | `[IMPLEMENTED]` | `[TBD]` A1 / F1 |
| R5 | `stream:true` → **SSE** `data: {…}` frames terminated by `data: [DONE]` | `[IMPLEMENTED]` SSE **and** bare-JSON-lines tolerated | `[TBD]` A2 / F2 |
| R6 | Stream delta at **`choices[0].delta.content`** | `[IMPLEMENTED]` | `[TBD]` A2 / F2 |
| R7 | Non-2xx → error body → `LlmException` → `ChatRules` offline fallback | `[IMPLEMENTED]`; call sites `ModelServlet.java` | `[TBD]` A3 / F3 |
| R8 | Unknown model → error, never a crash | `[IMPLEMENTED]` | `[TBD]` A3 / F3 |
| R9 | `finish_reason` present | not read by the transport | `[TBD]` A1 / F1 |
| R10 | Model identity readable at runtime | `[IMPLEMENTED]` via `LlmProvider.model()` | `[TBD]` A4 / F4 |

`[VENDOR]` Ollama and FastFlowLM both document an OpenAI-compatible `/v1` surface. Nothing in that
claim has been verified on either machine — hence every row's hardware status is `[TBD]`.

R5 is the one requirement where the tolerant parser is doing real work rather than being
defensive: Ollama's native endpoint emits NDJSON, and it is not yet established that Ollama's
`/v1` surface uses SSE either. The transport accepts both framings so that a negative probe result
does not require a code change.

## 3.2 Confirmed NOT required

`[CURRENT]` Verified by absence in the source. These must not gate the production model choice.

| Feature | Why it is not required |
|---|---|
| `tools` / `tool_calls` | Never referenced anywhere in the codebase |
| `response_format` / `json_schema` | Never used. `LlmIntentClassifier.parse()` extracts JSON with the regex `\{.*?}` (`LlmIntentClassifier.java:21,58-59`) |
| `top_p`, `presence_penalty`, frequency penalties | Never used |
| Multimodal `content[]` arrays | Never used — the SPA is text-only |
| `v1/embeddings` | Never used. Retrieval is keyword scoring (`ModelServlet.scoreChunk()`) |
| `usage` block | Not needed. Token counts are estimated locally by `Tokens.estimate()` (`ModelServlet.java:200`) |

## 3.3 Provider-specific, outside the OpenAI contract

| Feature | Scope | Status |
|---|---|---|
| `options.num_ctx` — per-request context length | `[VENDOR]` Ollama native only. `[VENDOR]` FastFlowLM sets context at `flm serve` time via `--ctx-len` and has no per-request equivalent | `[TBD]` verify probe A5; no F equivalent |
| `"think"` request flag | `[VENDOR]` FastFlowLM server mode only, non-standard; CLI uses `/think`. Not an OpenAI field | `[TBD]` verify probe F5; not applicable to Ollama |

## 3.4 `[PLANNED]` Capability contract

`[PLANNED]` The provider exposes capabilities so the application never guesses. This is the concrete realisation of section 3.1 and 3.3:

```java
EnumSet<LlmCapability> capabilities = ...
// LlmCapability: CHAT, STREAMING, SYSTEM_PROMPT, TOOL_CALLING, STRUCTURED_OUTPUT,
//                THINKING, MODEL_INFO, HEALTH_CHECK, MODEL_DISCOVERY,
//                PER_REQUEST_CONTEXT_LENGTH
```

`[PLANNED]` Only two capability bits actually differ in practice between the two providers today: `THINKING` (FastFlowLM only) and `PER_REQUEST_CONTEXT_LENGTH` (Ollama only). The remainder exist so the abstraction does not have to be reopened when a runtime adds something.

---

# 4. `[PLANNED]` Provider layer design

`[PLANNED]` Because both runtimes are `[VENDOR]` OpenAI-compatible, all HTTP, JSON and SSE handling collapses into a single transport class. The two providers become thin declarations.

```text
src/main/java/davejones74/campanionai/llm/
├── LlmProvider.java           interface
├── LlmCapabilities.java       EnumSet-backed record
├── LlmMessage.java            record(String role, String content)
├── LlmException.java
├── OpenAiCompatProvider.java  all transport: Jackson + java.net.http, SSE + [DONE],
│                              error mapping, timeouts
├── OllamaProvider.java        base URL default http://localhost:11434; num_ctx only
│                              when PER_REQUEST_CONTEXT_LENGTH
├── FastFlowLmProvider.java    base URL default http://127.0.0.1:52625; think flag
│                              only when THINKING
└── LlmProviderFactory.java    campanionai.llm.provider -> instance; unknown = startup failure
```

`[PLANNED]` Interface shape, chosen to keep the existing method names and return types so the consumer diff is minimal:

```java
public interface LlmProvider {
    String providerName();
    LlmCapabilities capabilities();
    String model();

    String chat(List<LlmMessage> messages) throws LlmException;

    void chatStream(List<LlmMessage> messages, ChunkHandler handler) throws LlmException;

    @FunctionalInterface
    interface ChunkHandler {
        void onDelta(String delta);
    }
}
```

## 4.1 `[IMPLEMENTED]` Base URL semantics

`[IMPLEMENTED]` `baseUrl` is the **runtime root**. The provider appends `/v1/chat/completions` and
`/v1/models`. This is deliberate: it means `campanionai.ollamaUrl=http://localhost:11434` keeps
exactly its original meaning, so the development workflow needed no migration.

## 4.2 `[IMPLEMENTED]` Behaviour that was preserved

The migration was a transport change, not an application rewrite. Each row below was carried over
intact, and each is now backed by a test rather than by inspection.

| Preserved behaviour | Now located in | Guarded by |
|---|---|---|
| Method names `chat` / `chatStream`, `String` return | `LlmProvider` | compile-time |
| `ChunkHandler` delta callback | `LlmProvider` | `OpenAiCompatTransportTest` |
| `LlmException` triggers the `ChatRules` offline fallback | `ModelServlet` | `LlmIntentClassifierTest` |
| Unparseable stream lines are skipped, not fatal | `OpenAiCompatTransport` | `OpenAiCompatTransportTest` |
| 10 s connect / 120 s non-stream / 300 s stream timeouts | `OpenAiCompatTransport` | constants |
| `campanionai.numCtx` continues to work on Ollama | `OllamaProvider` | Phase 4 |
| Model name still rendered in the UI badge | `ModelServlet` via `LlmProvider.model()` | compile-time |

One deliberate addition beyond preservation: `LlmProvider` exposes `temperature()` so the UI badge
reads model *and* temperature from the same provider instance that builds the request. Reading them
separately in `ModelServlet` would have let the displayed values drift from what was sent.

## 4.3 `[IMPLEMENTED]` Blast radius

`[IMPLEMENTED]` `LlmClient.ChatMessage` appeared in **public signatures**, so moving it touched
8 files. All 8 have been migrated to `LlmMessage`:

- `retrieval/IntentClassifier.java`
- `retrieval/LlmIntentClassifier.java`
- `retrieval/RuleIntentClassifier.java`
- `retrieval/RetrievalService.java`
- `ModelServlet.java`
- `src/test/.../LlmIntentClassifierTest.java` (repointed from Ollama `/api/chat` stubs to OpenAI-shaped `StubLlmServer`)
- `src/test/.../RuleIntentClassifierTest.java`
- `src/test/.../RetrievalServiceTest.java`

`[CURRENT]` `IntentClassifier` is already an interface with two implementations (`RuleIntentClassifier`, `LlmIntentClassifier`) selected by a factory call at `ModelServlet.java:132`. The codebase therefore already uses strategy + interface + factory, and the provider abstraction follows that existing house style.

`[CURRENT]` One stale reference exists outside source: `data/urls/e73b4d166ce09994c384bef95fc676c8dfdddbb7.txt` is a cached copy of the GitHub README fetched into the knowledge base. It will drift and is harmless.

## 4.4 `[PLANNED]` Configuration

`[PLANNED]` Additive only. Precedence: system property, then environment variable, then default.

| Property | Environment variable | Default |
|---|---|---|
| `campanionai.llm.provider` | `LLM_PROVIDER` | `ollama` |
| `campanionai.llm.baseUrl` | `LLM_BASE_URL` | per provider |
| `campanionai.llm.think` | `LLM_THINK` | **`false`** |
| `campanionai.model` | `LLM_MODEL` | `qwen3.6:27b` (unchanged) |
| `campanionai.temperature` | — | `0.7` (unchanged) |
| `campanionai.numCtx` | — | *(unset)* (unchanged; now capability-gated) |
| `campanionai.ollamaUrl` | — | retained as fallback for the Ollama base URL |

`[PLANNED]` `campanionai.llm.think` is the provider-neutral "reasoning enabled" concept. Its translation to FastFlowLM's non-standard `"think"` payload field happens inside the provider. Default is `false`. No UI toggle in v1.

`[PLANNED]` Context budgets (`maxContextTokens`, `historyTokens`, `chunkTokens`, `chunkOverlap`, `live.contextTokens`) are already configurable and need no new properties. Their production values are `[TBD]` pending the bake-off in `Qwen-Model-Evaluation.md`.

`[PLANNED]` Setting `campanionai.numCtx` against a provider without `PER_REQUEST_CONTEXT_LENGTH` logs a warning and is ignored. It is not an error, so a shared configuration file cannot break a host.

---

# 5. `[TBD]` Open questions about FastFlowLM

Every item in this section is unresolved. None may be assumed.

| # | Question | How to resolve |
|---:|---|---|
| Q1 | Which FastFlowLM release is installed on the X1 Pro? | `flm --version`, `dpkg -l \| grep -Ei 'fastflowlm\|xrt\|amdxdna'` |
| Q2 | Which NPU firmware, driver and kernel are present? | `flm validate`, `xrt-smi examine`, `uname -a`, `modinfo -F filename amdxdna` |
| Q3 | Is the API truly OpenAI-compatible, and on which subset? | Probes F1–F5 in `X1Pro-FastFlowLM-Validation.md` |
| Q4 | Does streaming use SSE with `data:` frames and a `data: [DONE]` terminator? | Probe F2 |
| Q5 | What is the error body shape and HTTP status for an unknown model? | Probe F3 |
| Q6 | Does the `"think"` field exist and where does reasoning text appear in the response? | Probe F5 |
| Q7 | Is `qwen3.6-moe:35b-a3b` present in the installed release's catalogue? | `flm pull qwen3.6-moe:35b-a3b` |
| Q8 | **What address does `flm serve` bind to — `127.0.0.1` or `0.0.0.0`?** | `ss -ltnp \| grep <port>` |
| Q9 | Is CORS enabled by default in the installed release? | `OPTIONS` preflight probe, Block 5 |
| Q10 | Is server mode stateless, and what is the measured prefill penalty? | Block 4 banner plus `llmBench` in Phase 4 |
| Q11 | What is the request queue / socket concurrency ceiling? | `[VENDOR]` `--q-len` and `--socket`; confirm empirically |
| Q12 | What is the production context budget at 4k / 8k / 16k? | `Qwen-Model-Evaluation.md` |

`[VENDOR]` FastFlowLM documentation states that the API has **no authentication** — a placeholder key is accepted. This makes Q8 a security question, not a preference. See `X1Pro-Home-Hosting-Architecture.md` §22.

---

# 6. Implementation order

## 6.1 Two gates, not one

This document originally imposed a single blanket rule: no Java implementation work begins until
Phase 0 hardware validation passes. **That rule was relaxed on operator instruction after Phases 1–3
completed**, because a single gate conflated two questions that have different answers.

| | Gate A — implementation | Gate B — deployment and performance |
|---|---|---|
| Question | May the application be restructured around a provider-neutral boundary? | May anything be asserted or deployed about the X1 Pro? |
| Answer | **Yes.** Satisfied. | **No.** Still closed. |
| Depends on hardware | No | Yes |
| Covers | Phases 1–3: `llm/` package, configuration, consumer rewiring | Phase 4 benchmarks, Phase 5 scripts, firewall/systemd, model selection |
| Rationale | The abstraction is runtime-neutral. Every vendor-specific field is confined to one provider class and asserted against a stub. No assumption about the target host enters application code, and the whole change is reversible. | Anything here becomes a claim about hardware, or a security control that depends on an observed bind address. Neither can be justified without measurement. |

### Why relaxing Gate A is defensible

The original rule existed to prevent rewriting the application around a runtime that had not been
shown to execute a model. That risk does not apply to a provider abstraction:

1. `ModelServlet` and the retrieval package now depend on `LlmProvider`, not on any runtime.
2. `OpenAiCompatTransport` is exercised by `OpenAiCompatTransportTest` against `StubLlmServer`,
   which asserts the exact bytes on the wire. Those tests are evidence.
3. If FastFlowLM turns out to be unusable, the correct outcome is deleting `FastFlowLmProvider`.
   No application code changes, and the Ollama path is untouched.

### What Gate B still forbids

Until `X1Pro-FastFlowLM-Validation.md` §12 is satisfied, none of the following may be written as
fact, shipped as a default, or marked `[VALIDATED]`:

- Any FastFlowLM port, bind address, or CORS default
- Any firewall, socket or systemd hardening directive
- Any throughput, latency, RAM-headroom or NPU-utilisation figure
- Any claim that a specific model tag loads or runs on the NPU
- Any statement that the X1 Pro deployment is production-ready

### The one place a vendor assumption reached shipped code

`FastFlowLmProvider.DEFAULT_BASE_URL` is `http://127.0.0.1:52625`, taken from vendor documentation
rather than measurement — the one instance of an unverified value embedded in the build.
`LlmProviderFactory` logs a startup warning whenever a non-Ollama provider runs on a default base
URL that the operator did not configure, so the value cannot pass silently. `[IMPLEMENTED]`

## 6.2 Phase status

| Phase | Content | Status |
|---:|---|---|
| 0 | This document, `X1Pro-FastFlowLM-Validation.md`, `Qwen-Model-Evaluation.md`, amend `X1Pro-Home-Hosting-Architecture.md` | Docs `[IMPLEMENTED]`; hardware validation `[TBD]` |
| 1 | `llm/` package: interface, capability record, message/exception types, shared transport, two providers, factory | `[IMPLEMENTED]` |
| 2 | Additive configuration properties | `[IMPLEMENTED]` |
| 3 | Mechanical consumer rewiring across the 8 files in §4.3; `LlmClient` deleted | `[IMPLEMENTED]` — 53 tests green |
| 4 | New provider tests; `llmBench` and `llmParity` Gradle tasks; run both providers on both hosts | `[PLANNED]` — blocked on Gate B for the X1 Pro half |
| 5 | `script/run.sh` consolidation; deployment unit and firewall | `[PLANNED]` — blocked on Gate B |

Phases 4 and 5 may proceed on the **development** host. Only their X1 Pro halves are gated.

---

# 7. Acceptance criteria

## Development (Intel + RTX 4090 + Ollama)

- [x] Existing Ollama workflow still works unchanged
- [x] `campanionai.ollamaUrl` and `campanionai.numCtx` still honoured
- [x] `campanionai.model` default unchanged
- [x] All pre-existing tests still pass — 40 methods across the 7 original classes, plus 13 new
      methods in `OpenAiCompatTransportTest` and `LlmIntentClassifierTest` = **53 green**
- [x] Probes A1–A5 recorded in `Qwen-Model-Evaluation.md`

## X1 Pro (HX-370 + XDNA2 + FastFlowLM)

- [ ] XDNA2 NPU detected
- [ ] `flm validate` succeeds
- [ ] `xrt-smi examine` lists the NPU
- [ ] A supported Qwen model runs on the NPU
- [ ] FastFlowLM API reachable from localhost
- [ ] Probes F1–F5 recorded
- [ ] CompanionAI can talk to FastFlowLM
- [ ] Streaming works
- [ ] Required features R1–R10 all satisfied
- [ ] Authentication works (CompanionAI side)
- [ ] FastFlowLM is not reachable from the public Internet
- [ ] FastFlowLM is not unnecessarily reachable from the LAN

## Portability

- [x] Runtime selectable through configuration
- [x] Model selectable through configuration
- [x] No NVIDIA-specific code in application logic
- [x] No AMD or XDNA-specific code in application logic
- [x] All provider-specific behaviour isolated in the `llm/` package
- [x] The only runtime-name string literals live in `LlmProviderFactory` and the two provider classes