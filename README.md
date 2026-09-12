# CompanionAI

A small Java web application that lets you chat with a local language model
(Ollama) which is backed by a persistent, uploadable knowledge base of
documents (`.txt`, `.docx`, `.pdf`).

## Features

- **Local LLM chat**: powered by Ollama (default model `qwen3.6:27b`), giving a
  strong base level of conversational intelligence. Replies **stream** to the
  page word-by-word as the model generates them, so responses feel instant.
- **Document knowledge base**: documents in the `data/` folder are loaded on
  startup. For each question, only the **most relevant** documents (keyword
  scoring, default top 3) are injected into the model's context, so large
  knowledge bases stay fast.
- **Upload documents**: add `.txt`, `.docx`, or `.pdf` files through the web UI.
  They are extracted to text, saved in `data/`, and immediately become part of
  the knowledge base.
- **Persistence**: uploaded documents live in the `data/` folder and survive
  restarts — no re-training or degradation over time.
- **Offline fallback**: if Ollama is unreachable, a small rule-based layer
  (`ChatRules`) answers common greetings and small talk until the LLM is back.
- **Polished chat UI**: scrollable chat history, multi-line input (4 rows,
  auto-growing, cursor-resizable), thinking animation while waiting for a
  reply, and subtle audio tones on send/reply.
- **Embedded Tomcat** web server with JSON API (`/api/chat`, `/api/chat/stream`,
  `/upload`).

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
2. **Upload**: the upload bar is pinned to the bottom of the screen at all
   times. Choose a `.txt`, `.docx`, or `.pdf` file, press **Upload**, and it
   immediately joins the knowledge base.
3. Documents you drop directly into the `data/` folder are picked up on the next
   startup.

## Configuration

All settings are system properties with defaults:

| Property                  | Default                  | Description                     |
|---------------------------|--------------------------|---------------------------------|
| `campanionai.model`       | `qwen3.6:27b`            | Ollama model name               |
| `campanionai.ollamaUrl`   | `http://localhost:11434` | Ollama base URL                 |
| `campanionai.temperature` | `0.7`                    | Sampling temperature            |
| `campanionai.maxDocs`     | `3`                      | Max docs injected per question  |

Example: `./gradlew run -Dcampanionai.model=deepseek-r1:latest`

## Project layout

```
src/main/java/davejones74/campanionai/
├── Main.java              # Simple entry point
├── Server.java            # Starts embedded Tomcat, maps URL routes
├── ModelServlet.java      # HTTP handlers + embedded SPA HTML/CSS/JS
├── LlmClient.java         # Thin HTTP client for the Ollama chat API
├── ChatRules.java         # Rule-based offline fallback (LLM unavailable)
└── DocumentReader.java    # Extracts text from .txt / .docx / .pdf
src/main/resources/log4j2.xml   # Logging configuration (console)
data/                      # Knowledge base documents (created at runtime)
└── greetings.txt, exchanges.txt, formal.txt, casual.txt, farewells.txt,
    questions.txt          # Bundled greeting documents
```

## Notes

- The intelligence comes from a local LLM via Ollama, not from code. The Java
  side is a thin HTTP client plus a document loader — swapping the model is a
  one-line config change (`campanionai.model`).
- The browser UI is a single-page app: chat uses streaming `fetch` over
  Server-Sent Events (`/api/chat/stream`), so tokens render as they arrive and
  the chat history persists without a full reload. Audio tones are generated
  using the Web Audio API (no audio files required) — tones play on send and
  again when a reply completes.
- Since the knowledge base is scored per question, uploading large documents no
  longer slows down every request — only the docs that match your message (up
  to `campanionai.maxDocs`) are sent to the model.
- This project began as a learning exercise (a from-scratch MLP language model
  and a rule engine). Those have been replaced by the Ollama-backed approach;
  `ChatRules` remains only as an offline fallback.
- Knowledge-base documents live in `data/`, which is excluded from version
  control via `.gitignore`. Any `.txt/.docx/.pdf` dropped there is loaded on
  startup.