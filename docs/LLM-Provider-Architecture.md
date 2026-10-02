# CompanionAI LLM Provider Architecture

Status legend used throughout this document:

| Marker | Meaning |
|---|---|
| `[CURRENT]` | Established behaviour, read directly from this repository's source |
| `[PLANNED]` | Design decision taken, not yet implemented |
| `[VALIDATED]` | Verified by measurement on hardware. **Nothing in this document holds this marker yet.** |
| `[TBD]` | Unknown. Must be determined by running the probes in this document or `X1Pro-FastFlowLM-Validation.md` |
| `[VENDOR]` | Stated by upstream vendor documentation. Trusted as a claim, not as a measurement |

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

`[PLANNED]` Runtime selection and model selection are configuration concerns. The same application artefact is expected to serve both hosts without source changes.

---

# 2. `[CURRENT]` The existing LLM contract

`[CURRENT]` There is **no provider abstraction today**. The entire LLM surface is one class, `src/main/java/davejones74/campanionai/LlmClient.java`, hard-wired to Ollama's **native** (non-OpenAI) API.

## 2.1 `[CURRENT]` Wire contract

Request (`LlmClient.java:48-75`):

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

Error convention `[CURRENT]`: a `{"error": "..."}` field is treated as a failure **even when the HTTP status is 200** (`LlmClient.java:83-86`, `:136-139`). Genuine non-200 responses also raise an error (`LlmClient.java:66-68`, `:120-123`).

Timeouts `[CURRENT]`: 10 s connect; 120 s non-streaming request; 300 s streaming request.

Public surface `[CURRENT]`:

| Member | Purpose |
|---|---|
| `String chat(List<ChatMessage>) throws LlmException` | non-streaming reply text |
| `void chatStream(List<ChatMessage>, ChunkHandler) throws LlmException` | delta callback |
| `record ChatMessage(String role, String content)` | message type |
| `@FunctionalInterface ChunkHandler { void onDelta(String delta) }` | stream callback |
| `static class LlmException extends Exception` | failure type |

## 2.2 `[CURRENT]` Ollama coupling points to remove

| # | Location | Coupling |
|---:|---|---|
| 1 | `LlmClient.java:58,112` | Endpoint path `"/api/chat"` — Ollama-native |
| 2 | `LlmClient.java:87,141` | Reply path `message.content` — Ollama-native |
| 3 | `LlmClient.java:83,136` | `{"error": ...}` at HTTP 200 — Ollama-native error convention |
| 4 | `LlmClient.java:129` | **NDJSON** stream framing — Ollama-native |
| 5 | `LlmClient.java:40-46` | `options.num_ctx` — per-request context override, Ollama-only |
| 6 | `LlmClient.java:16,67,73,78,85,89,95,122,138,148` | `"Ollama"` embedded in Javadoc, exception and log strings |
| 7 | `ModelServlet.java:50` | `campanionai.model` default `qwen3.6:27b` — an Ollama tag |
| 8 | `ModelServlet.java:51` | `campanionai.ollamaUrl`, default `http://localhost:11434` |
| 9 | `ModelServlet.java:56,109` | `campanionai.numCtx` wired straight into #5 |
| 10 | `script/switch-model.sh:17-38` | Shells out to the `ollama` CLI to validate the model exists |
| 11 | `script/run-*.sh` (9 files) | Bake in the Ollama-only launch workflow |
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

# 3. `[CURRENT]` What CompanionAI actually requires from an LLM

This is the load-bearing section. The required set is **smaller** than an OpenAI client would normally assume, and every entry is justified by a specific call site.

## 3.1 Required features

| # | Feature | Current evidence | Status |
|---:|---|---|---|
| R1 | `POST /v1/chat/completions` | replaces `/api/chat` (`LlmClient.java:58,112`) | `[TBD]` verify probe A1 / F1 |
| R2 | `messages[].role` ∈ `system`,`user`,`assistant` | `messagesJson()` `LlmClient.java:152-160` | `[TBD]` A1 / F1 |
| R3 | Top-level `temperature` | `options()` `LlmClient.java:41` | `[TBD]` A1 / F1 |
| R4 | Non-stream reply at **`choices[0].message.content`** | `LlmClient.java:87` | `[TBD]` A1 / F1 |
| R5 | `stream:true` → **SSE** `data: {…}` frames terminated by `data: [DONE]` | `LlmClient.java:118-142` (NDJSON today) | `[TBD]` A2 / F2 |
| R6 | Stream delta at **`choices[0].delta.content`** | `LlmClient.java:141` | `[TBD]` A2 / F2 |
| R7 | Non-2xx → error body → `LlmException` → `ChatRules` offline fallback | `LlmClient.java:66-68,120-123`; `ModelServlet.java:463,529` | `[TBD]` A3 / F3 |
| R8 | Unknown model → error, never a crash | `LlmClient.java:83-86` | `[TBD]` A3 / F3 |
| R9 | `finish_reason` present | not read today; needed to detect truncated streams | `[TBD]` A1 / F1 |
| R10 | Model identity readable at runtime | `ModelServlet.page()` `ModelServlet.java:685` | `[TBD]` A4 / F4 |

`[VENDOR]` Ollama and FastFlowLM both document an OpenAI-compatible `/v1` surface. Nothing in that claim has been verified on either machine yet — hence every row above is `[TBD]`.

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

## 4.1 `[PLANNED]` Base URL semantics

`[PLANNED]` `baseUrl` is the **runtime root**. The provider appends `/v1/chat/completions` and `/v1/models`. This is deliberate: it means `campanionai.ollamaUrl=http://localhost:11434` keeps exactly its current meaning, so the development workflow needs no migration.

## 4.2 `[PLANNED]` Behaviour that must be preserved

`[PLANNED]` The migration is a transport change, not an application rewrite. The following `[CURRENT]` behaviour must survive it, each covered by a test:

| Preserved behaviour | Current location |
|---|---|
| Method names `chat` / `chatStream`, `String` return | `LlmClient.java:80,102` |
| `ChunkHandler` delta callback | `LlmClient.java:169-171` |
| `LlmException` triggers the `ChatRules` offline fallback | `ModelServlet.java:463,529` |
| Unparseable stream lines are skipped, not fatal | `LlmClient.java:131-135` |
| 10 s connect / 120 s non-stream / 300 s stream timeouts | `LlmClient.java:24-26,60,114` |
| `campanionai.numCtx` continues to work on Ollama | `ModelServlet.java:56,109` |
| Model name still rendered in the UI badge | `ModelServlet.java:685` |

## 4.3 `[PLANNED]` Blast radius

`[CURRENT]` `LlmClient.ChatMessage` appears in **public signatures**, so moving it touches 8 files:

- `retrieval/IntentClassifier.java:10`
- `retrieval/LlmIntentClassifier.java:27,31,37`
- `retrieval/RuleIntentClassifier.java:47`
- `retrieval/RetrievalService.java:39`
- `ModelServlet.java:83,408-424,455,513`
- `src/test/.../LlmIntentClassifierTest.java` (3 tests, Ollama-shaped stubs)
- `src/test/.../RuleIntentClassifierTest.java:16`
- `src/test/.../RetrievalServiceTest.java:19,28`

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

`[PLANNED]` Phase 0 documentation and hardware validation gate every subsequent phase.

| Phase | Content | Gate |
|---:|---|---|
| 0 | This document, `X1Pro-FastFlowLM-Validation.md`, `Qwen-Model-Evaluation.md`, amend `X1Pro-Home-Hosting-Architecture.md` | `flm validate` succeeds **and** one supported Qwen model runs on the NPU |
| 1 | `llm/` package: interface, capability record, message/exception types, shared transport, two providers, factory | — |
| 2 | Additive configuration properties | — |
| 3 | Mechanical consumer rewiring across the 8 files in §4.3 | Existing test suite green |
| 4 | New provider tests; `llmBench` and `llmParity` Gradle tasks; run both providers on both hosts | Bake-off complete |
| 5 | `script/run.sh` consolidation; deployment unit and firewall | Acceptance criteria met |

`[PLANNED]` Phase 1 must not begin until Phase 0's gate is satisfied. Per the project's own working rule: do not rewrite the application around a runtime that has not yet been shown to execute a model on the target hardware.

---

# 7. Acceptance criteria

## Development (Intel + RTX 4090 + Ollama)

- [ ] Existing Ollama workflow still works unchanged
- [ ] `campanionai.ollamaUrl` and `campanionai.numCtx` still honoured
- [ ] `campanionai.model` default unchanged
- [ ] All 40 existing test methods across 7 test classes pass
- [ ] Probes A1–A5 recorded in `Qwen-Model-Evaluation.md`

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

- [ ] Runtime selectable through configuration
- [ ] Model selectable through configuration
- [ ] No NVIDIA-specific code in application logic
- [ ] No AMD or XDNA-specific code in application logic
- [ ] All provider-specific behaviour isolated in the `llm/` package
- [ ] The only runtime-name string literals live in `LlmProviderFactory` and the two provider classes