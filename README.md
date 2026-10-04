# CompanionAI

A small Java web application that lets you chat with a local language model
(Ollama) which is backed by a persistent, uploadable knowledge base of
documents (`.txt`, `.docx`, `.pdf`) and **web articles**.

## Features

- **Local LLM chat**: powered by a provider-neutral client — Ollama by default
  (model `qwen3.6:27b`), or FastFlowLM on an AMD NPU such as the Ryzen AI /
  XDNA2 hardware in the Minisforum X1 Pro. Replies **stream** to the page
  word-by-word as the model generates them, so responses feel instant.
- **Read web articles**: paste any `http(s)` URL into the chat box and
  CompanionAI fetches the page, extracts the readable text, stores it in the
  knowledge base, and answers using it. Fetched pages are cached by URL and
  survive restarts.
- **Conversation memory**: the app keeps a capped rolling history of recent
  turns, so you can have multi-turn conversations ("and what about its climate?")
  without losing the thread.
- **Usage statistics**: a live stats panel shows questions answered, streamed /
  offline replies, URL fetches, estimated tokens in/out, and reply latency.
- **Document knowledge base**: documents in the `data/` folder are loaded on
  startup. Large documents are split into chunks, and for each question only the
  **most relevant chunks** (keyword scoring, within a token budget) are injected
  into the model's context, so even big knowledge bases stay fast and never
  overflow the model's window.
- **Upload documents**: add `.txt`, `.docx`, or `.pdf` files through the web UI.
  They are extracted to text, saved in `data/`, and immediately become part of
  the knowledge base.
- **Persistence**: uploaded documents and fetched articles live in `data/` and
  survive restarts — no re-training or degradation over time.
- **Offline fallback**: if the LLM runtime is unreachable, a small rule-based
  layer (`ChatRules`) answers common greetings and small talk until it returns.
- **Optional access token**: set `campanionai.authToken` and a lightweight
  sign-in page protects the app — suited to cloud hosting.
- **Polished chat UI**: scrollable chat history, multi-line input (4 rows,
  auto-growing, cursor-resizable), thinking animation while waiting for a
  reply, and subtle audio tones on send/reply.
- **LLM-created downloadable files**: when the active model supports tool
  calling (`LlmCapability.TOOL_CALLING`), it can request a generated file via a
  CompanionAI-owned `create_file` tool (Markdown, text, JSON, CSV). Files are
  written under `data/generated/`, validated for safe filenames, type and size,
  and served through the authenticated `/api/files/{name}` endpoint as
  download links in the chat UI.
- **Reported sources**: answers built from fetched web content or live
  retrieval list the actual external source URLs beneath the reply. Sources and
  files are preserved per message in chat history.
- **Context meter**: the footer bar shows the percentage of the configured
  context window the assembled prompt occupies (`Context: N% (used / limit)`),
  computed from the real system prompt, history, retrieval results and user
  message.
- **Persistent chats**: conversations are stored as JSON under `data/chats/`
  and survive restarts. A sidebar supports new, switching, renaming and
  deleting chats; the first user message seeds the initial title.
- **Embedded Tomcat** web server with JSON API (`/api/chat`, `/api/chat/stream`,
  `/api/stats`, `/api/chats`, `/api/files`, `/upload`).
- **Live information retrieval**: the assistant can answer current-information
  questions by pulling in **web search results** (Tavily), **weather** (Open-Meteo,
  no key needed), or **football standings & fixtures** (API-Football). Retrieval
  is best-effort: missing API keys or provider hiccups simply leave the model
  answering from its own knowledge, with a short notice. Retrieved content is
  treated as untrusted data and never stored in the knowledge base.

## Requirements

- JDK 26
- Gradle (the included wrapper `gradlew.bat` is used)
- **A local LLM runtime.** Either:
  - **Ollama** at `http://localhost:11434` with a model pulled
    (`ollama pull qwen3.6:27b`), or
  - **FastFlowLM** on an AMD NPU (XDNA2), e.g. the Ryzen AI 9 HX 370 in the
    Minisforum X1 Pro, serving on `http://127.0.0.1:52625`.

  The FastFlowLM path has been run against real NPU hardware — see
  `docs/X1Pro-FastFlowLM-Validation.md`. Ollama is the development default.

## Build & Run

```bash
./gradlew build
./gradlew run
```

Then open [http://localhost:8080/](http://localhost:8080/) in your browser.

## Usage

1. **Chat**: type a message in the multi-line text area (4 rows, auto-growing)
   and press **Send** (or *Enter*). Replies stream into a scrollable chat
   history — you can scroll back to see earlier messages.
2. **Read a web article**: paste a URL (e.g. a Wikipedia article) as part of
   your message. The app fetches it, saves it, and answers using its contents.
3. **Upload**: the upload bar is pinned to the bottom of the screen at all
   times. Choose a `.txt`, `.docx`, or `.pdf` file, press **Upload**, and it
   immediately joins the knowledge base.
4. Documents you drop directly into the `data/` folder are picked up on the next
   startup.

## Switching models and runtimes

One launcher covers every runtime and model:

```bash
./script/run.sh ollama gemma4:12b            # Ollama, dev host
./script/run.sh fastflowlm qwen2.5-it:3b     # FastFlowLM on an AMD NPU
```

`run.sh` resolves the provider's base URL, checks that the model is actually
installed on the chosen runtime, builds the distribution on first run, warns if
something is already listening on port 8080, and launches CompanionAI.

The check matters more than it looks: FastFlowLM does not reject an unknown
model tag — the request simply hangs. `run.sh` turns that silent hang into an
immediate, actionable error.

Two environment variables override the defaults:

```bash
COMPANIONAI_LLM_BASE_URL=http://127.0.0.1:52625 ./script/run.sh fastflowlm qwen2.5-it:3b
COMPANIONAI_LLM_NUM_CTX=8192 ./script/run.sh ollama gemma4:12b
```

`COMPANIONAI_LLM_NUM_CTX` is Ollama-only. FastFlowLM fixes the context length
when the runtime starts, so on that provider the value warns and is ignored.

### Older per-model scripts

The nine `script/run-<model>.sh` wrappers still work and still target Ollama.
They predate the provider abstraction; prefer `run.sh` for anything new.

To switch without launching:

```bash
./script/switch-model.sh qwen2.5:14b
```

## Configuration

All settings are system properties with defaults:

### LLM provider

These keys are provider-neutral and take precedence over the legacy Ollama-only
keys below. Each also has an environment-variable equivalent, looked up in the
order system property → environment variable → default.

| Property                    | Env var           | Default          | Description                                                        |
|-----------------------------|-------------------|------------------|--------------------------------------------------------------------|
| `companionai.llm.provider`  | `LLM_PROVIDER`    | `ollama`         | `ollama` or `fastflowlm`. An unknown value fails startup            |
| `companionai.llm.model`     | `LLM_MODEL`       | `qwen3.6:27b`    | Model identifier sent to the runtime                               |
| `companionai.llm.baseUrl`   | `LLM_BASE_URL`    | per provider     | Runtime root URL; `/v1/...` is appended                             |
| `companionai.llm.temperature` | `LLM_TEMPERATURE` | `0.7`          | Sampling temperature                                               |
| `companionai.llm.numCtx`    | `LLM_NUM_CTX`     | *(unset)*        | Context window. Ollama only; ignored with a warning elsewhere      |
| `companionai.llm.think`     | `LLM_THINK`       | `false`          | `fastflowlm` only; enables reasoning output                        |

> The default FastFlowLM base URL (`http://127.0.0.1:52625`) is the port
> `flm port` reports, and it has been confirmed working against FastFlowLM
> `1.0.7` on the X1 Pro. CompanionAI still warns if you rely on it without
> setting `LLM_BASE_URL`, because the value comes from vendor documentation
> rather than from configuration you set — see
> `docs/X1Pro-FastFlowLM-Validation.md` §0.2.

The legacy keys below still work unchanged for Ollama.

### All other settings

| Property                  | Default          | Description                                              |
|---------------------------|------------------|----------------------------------------------------------|
| `campanionai.model`       | `qwen3.6:27b`    | Ollama model name (superseded by `companionai.llm.model`) |
| `campanionai.ollamaUrl`   | `http://localhost:11434` | Ollama base URL (superseded by `companionai.llm.baseUrl`) |
| `campanionai.temperature` | `0.7`            | Sampling temperature (superseded by `companionai.llm.temperature`) |
| `campanionai.numCtx`      | *(unset)*        | Ollama context window (`num_ctx`) (superseded by `companionai.llm.numCtx`) |
| `campanionai.dataDir`     | `./data`         | Knowledge base folder                                    |
| `campanionai.host`        | `0.0.0.0`        | Bind address (cloud: leave as is)                        |
| `campanionai.port`        | `8080`           | HTTP port                                                |
| `campanionai.authToken`   | *(unset)*        | If set, requires this access token to use the app        |
| `campanionai.maxDocs`     | `3`              | Max chunks per document per question                     |
| `campanionai.chunkTokens` | `1500`           | Approx tokens per document chunk                         |
| `campanionai.chunkOverlap`| `200`            | Tokens of overlap between adjacent chunks                |
| `campanionai.maxContextTokens` | `20000`     | Max total context tokens injected per question           |
| `campanionai.historyTokens` | `8000`         | Max history tokens retained for conversation memory      |
| `campanionai.historyMessages` | `40`         | Max history messages retained                            |
| `campanionai.maxUrlsPerMessage` | `1`       | Max URLs fetched per message                             |
| `campanionai.maxFetchBytes` | `2097152`      | Max bytes fetched per page                               |
| `campanionai.allowPrivateFetch` | `false`   | Allow fetching private/localhost URLs (SSRF guard)       |
| `campanionai.live.enabled` | `true`    | Master switch for live information retrieval              |
| `campanionai.live.intent` | `llm`      | Intent detection: `llm`, or `rules` for zero LLM latency  |
| `campanionai.searchApiKey` | `''`      | Tavily API key (web search) — unset ⇒ web search off     |
| `campanionai.sportsApiKey` | `''`      | API-Football key (football data) — unset ⇒ sports off    |
| `campanionai.live.defaultLocation` | `''` | Default weather location (e.g. `Uxbridge, UK`)           |
| `campanionai.live.webResults` | `5`      | Max web search results per query                          |
| `campanionai.live.fetchPages` | `2`      | Max live web pages to fetch per search                    |
| `campanionai.live.contextTokens` | `4000` | Token budget for injected live content                   |
| `campanionai.sports.maxRequestsPerDay` | `100` | API-Football daily request cap                        |

Examples:

```bash
# different model
./gradlew run -Dcampanionai.model=deepseek-r1:latest
# FastFlowLM on an AMD NPU
./gradlew run -Dcompanionai.llm.provider=fastflowlm \
  -Dcompanionai.llm.model=qwen2.5-it:3b \
  -Dcompanionai.llm.baseUrl=http://127.0.0.1:52625
# cloud deployment with a public address + access token
./gradlew run -Dcampanionai.host=0.0.0.0 -Dcampanionai.authToken=change-me \
  -Dcampanionai.allowPrivateFetch=false
# live information: web search (Tavily), weather for Uxbridge, and football
./gradlew run -Dcampanionai.searchApiKey=TVLY-xxxx -Dcampanionai.sportsApiKey=API-Football-xxxx \
  -Dcampanionai.live.defaultLocation="Uxbridge, UK"
```

## Cloud and public hosting

CompanionAI binds `0.0.0.0` by default and is designed to run on a single host
alongside its LLM runtime. Recommended settings for a public instance:

- Set `campanionai.authToken` so a sign-in page protects the app. **This is the
  control that matters** — the app itself is the only thing in the chain that
  needs protecting.
- Keep `campanionai.allowPrivateFetch=false` so the app can't be used to probe
  internal network addresses (SSRF).
- Point `campanionai.dataDir` at persistent storage (e.g. a mounted volume).
- Keep the LLM runtime on loopback and do not publish its API.

On the X1 Pro, FastFlowLM's `flm serve` defaults to binding `127.0.0.1` and was
confirmed unreachable from both the LAN and the public Internet, so no extra
network control is needed for it — that was verified, not assumed. CompanionAI's
own `0.0.0.0:8080` binding is what you must front with a reverse proxy or
restrict at the firewall. See `docs/X1Pro-Home-Hosting-Architecture.md`.

## Project layout

```
src/main/java/davejones74/campanionai/
├── Server.java            # Starts embedded Tomcat, maps URL routes, auth filter
├── ModelServlet.java      # HTTP handlers + embedded SPA HTML/CSS/JS
├── llm/                   # Provider abstraction: LlmProvider, OllamaProvider,
│                          #   FastFlowLmProvider, shared OpenAI-compatible transport
├── WebFetcher.java        # Fetches and extracts web articles (SSRF-guarded)
├── AuthFilter.java        # Optional token sign-in filter for cloud hosting
├── UsageStats.java        # Thread-safe usage counters exposed via /api/stats
├── ChatRules.java         # Rule-based offline fallback (LLM unavailable)
├── DocumentReader.java    # Extracts text from .txt / .docx / .pdf
├── Tokens.java            # Shared char-based token estimation
├── retrieval/             # Live information retrieval (web/weather/sports)
│   ├── RetrievalService.java   # Intent detection + provider dispatch
│   ├── RuleIntentClassifier.java, LlmIntentClassifier.java
│   ├── WebSearchProvider.java, WeatherProvider.java, SportsProvider.java
│   └── ... core types (Intent, RetrievalKind, Freshness, HttpHelper, ...)
src/main/resources/log4j2.xml   # Logging configuration (console)
data/                      # Knowledge base documents (created at runtime)
├── *.txt / *.docx / *.pdf     # Your documents and bundled greetings
└── urls/                      # Fetched web articles (cached by URL hash)
```

## API

| Endpoint               | Method | Behaviour                                          |
|------------------------|--------|-----------------------------------------------------|
| `/`                    | GET    | The SPA (or the sign-in page if auth is enabled)    |
| `/api/chat`            | POST   | JSON reply `{"message": "..."}` → `{reply, offline}` |
| `/api/chat/stream`     | POST   | Server-Sent Events: `delta` / `status` / `source` / `file` / `metadata` / `error` / `done` |
| `/api/stats`           | GET    | JSON usage statistics                               |
| `/api/chats`           | GET    | List chats                                          |
| `/api/chats`           | POST   | Create a chat                                       |
| `/api/chats/{id}`      | GET    | Chat metadata + persisted messages                  |
| `/api/chats/{id}`      | PATCH  | Rename (`{"title": "..."}`)                         |
| `/api/chats/{id}`      | DELETE | Delete the chat and its messages                    |
| `/api/files/{name}`    | GET    | Download a generated file (from `data/generated/`)  |
| `/api/auth`            | POST   | Sign in (when auth enabled): `{"token": "..."}`     |
| `/api/shutdown`        | POST   | Gracefully stop the server (localhost only)         |
| `/upload`              | POST   | Multipart document upload → joins the knowledge base |

To stop a running server, use `./gradlew shutdown` (works on any port via
`-Dcampanionai.port`). It posts to the localhost-only `/api/shutdown` endpoint
and stops the embedded Tomcat gracefully.

## Notes

- The intelligence comes from a local LLM, not from code. The Java side is a
  thin HTTP client plus a document loader; swapping the model or the runtime is
  a one-line config change (`companionai.llm.model` / `companionai.llm.provider`).
  Both supported runtimes speak the OpenAI chat-completions shape, so one
  transport serves both and vendor-specific request fields stay confined to their
  provider class.
- The browser UI is a single-page app: chat uses streaming `fetch` over
  Server-Sent Events (`/api/chat/stream`), so tokens render as they arrive and
  the chat history persists without a full reload. Audio tones are generated
  using the Web Audio API (no audio files required) — tones play on send and
  again when a reply completes.
- Since the knowledge base is chunked and scored per question, uploading large
  documents no longer slows down every request — only the chunks that match
  your message (up to `campanionai.maxContextTokens`) are sent to the model.
  Documents are split at a fixed character estimate (~4 chars/token), so very
  large articles never overflow the model's context window.
- Fetched articles are stored under `data/urls/` as text files headed by
  `# URL:` / `# Title:` / `# Fetched:`. They are deduplicated by URL and treated
  as ordinary knowledge-base documents on restart.
- Token estimates are approximate (chars ÷ 4); this is sufficient to keep
  prompts inside the window but is not a true tokenizer.
- Live information retrieval (web/weather/sports) is **best-effort and never
  blocking**: a question that can't be answered externally is simply answered
  from the model's own knowledge, with a short notice. Content pulled in via the
  live providers is injected into the prompt inside `<retrieved-content>` tags,
  is treated as untrusted data, and is never persisted to `data/`.
- This project began as a learning exercise (a from-scratch MLP language model
  and a rule engine). Those have been replaced by the Ollama-backed approach;
  `ChatRules` remains only as an offline fallback.
- Knowledge-base documents live in `data/`, which is excluded from version
  control via `.gitignore`. Any `.txt/.docx/.pdf` dropped there is loaded on
  startup.