# CompanionAI

A small Java web application that lets you chat with a local language model
(Ollama) which is backed by a persistent, uploadable knowledge base of
documents (`.txt`, `.docx`, `.pdf`) and **web articles**.

## Features

- **Local LLM chat**: powered by Ollama (default model `qwen3.6:27b`), giving a
  strong base level of conversational intelligence. Replies **stream** to the
  page word-by-word as the model generates them, so responses feel instant.
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
- **Offline fallback**: if Ollama is unreachable, a small rule-based layer
  (`ChatRules`) answers common greetings and small talk until the LLM is back.
- **Optional access token**: set `campanionai.authToken` and a lightweight
  sign-in page protects the app — suited to cloud hosting.
- **Polished chat UI**: scrollable chat history, multi-line input (4 rows,
  auto-growing, cursor-resizable), thinking animation while waiting for a
  reply, and subtle audio tones on send/reply.
- **Embedded Tomcat** web server with JSON API (`/api/chat`, `/api/chat/stream`,
  `/api/stats`, `/upload`).
- **Live information retrieval**: the assistant can answer current-information
  questions by pulling in **web search results** (Tavily), **weather** (Open-Meteo,
  no key needed), or **football standings & fixtures** (API-Football). Retrieval
  is best-effort: missing API keys or provider hiccups simply leave the model
  answering from its own knowledge, with a short notice. Retrieved content is
  treated as untrusted data and never stored in the knowledge base.

## Requirements

- JDK 26
- Gradle (the included wrapper `gradlew.bat` is used)
- **Ollama** running locally (default at `http://localhost:11434`) with a model
  pulled, e.g. `ollama pull qwen3.6:27b`

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

## Switching models

One script per installed model lives in `script/` (Git Bash):

```bash
# Switch the active model and start the app with it
./script/run-gemma4-12b.sh          # -> gemma4:12b
./script/run-deepseek-r1-32b.sh     # -> deepseek-r1:32b
```

Each `run-*.sh` first calls `script/switch-model.sh <model>`, which verifies the
model exists in Ollama (prints "Ollama is not running" or lists available models
otherwise), records it in `script/current-model.txt`, and aborts on failure.
On success the app is launched with that model via `campanionai.model`.

To switch without launching, or for a model without a run script:

```bash
./script/switch-model.sh qwen2.5:14b
```

## Configuration

All settings are system properties with defaults:

| Property                  | Default          | Description                                              |
|---------------------------|------------------|----------------------------------------------------------|
| `campanionai.model`       | `qwen3.6:27b`    | Ollama model name                                        |
| `campanionai.ollamaUrl`   | `http://localhost:11434` | Ollama base URL                                  |
| `campanionai.temperature` | `0.7`            | Sampling temperature                                     |
| `campanionai.numCtx`      | *(unset)*        | Ollama context window (`num_ctx`) for the model          |
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
# cloud deployment with a public address + access token
./gradlew run -Dcampanionai.host=0.0.0.0 -Dcampanionai.authToken=change-me \
  -Dcampanionai.allowPrivateFetch=false
# live information: web search (Tavily), weather for Uxbridge, and football
./gradlew run -Dcampanionai.searchApiKey=TVLY-xxxx -Dcampanionai.sportsApiKey=API-Football-xxxx \
  -Dcampanionai.live.defaultLocation="Uxbridge, UK"
```

## Cloud hosting

CompanionAI binds `0.0.0.0` by default and is designed to run on a single GPU
cloud box alongside Ollama. Recommended settings for a public instance:

- Set `campanionai.authToken` so a sign-in page protects the app.
- Keep `campanionai.allowPrivateFetch=false` so the app can't be used to probe
  internal network addresses (SSRF).
- Point `campanionai.dataDir` at persistent storage (e.g. a mounted volume).
- Run Ollama on the same host; set `campanionai.ollamaUrl` if it differs.

## Project layout

```
src/main/java/davejones74/campanionai/
├── Server.java            # Starts embedded Tomcat, maps URL routes, auth filter
├── ModelServlet.java      # HTTP handlers + embedded SPA HTML/CSS/JS
├── LlmClient.java         # Thin HTTP client for the Ollama chat API
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
| `/api/chat/stream`     | POST   | Server-Sent Events: `delta` / `status` / `error` / `done` |
| `/api/stats`           | GET    | JSON usage statistics                               |
| `/api/auth`            | POST   | Sign in (when auth enabled): `{"token": "..."}`     |
| `/api/shutdown`        | POST   | Gracefully stop the server (localhost only)         |
| `/upload`              | POST   | Multipart document upload → joins the knowledge base |

To stop a running server, use `./gradlew shutdown` (works on any port via
`-Dcampanionai.port`). It posts to the localhost-only `/api/shutdown` endpoint
and stops the embedded Tomcat gracefully.

## Notes

- The intelligence comes from a local LLM via Ollama, not from code. The Java
  side is a thin HTTP client plus a document loader — swapping the model is a
  one-line config change (`campanionai.model`).
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