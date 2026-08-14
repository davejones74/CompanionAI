# CompanionAI

A small Java web application that combines a simple Multi-Layer Perceptron
(MLP) language model with a rule-based conversational layer, and lets you
interact with it in a browser. This is a first attempt / learning project — an
experiment in building something chat-like from scratch.

## Features

- **Rule-based conversational layer** (`ChatRules`): handles greetings,
  identity, mood, jokes, knock-knock routines, small talk, live time/date, and
  light arithmetic. Varied, randomised replies.
- **MLP language model** (`MlpLanguageModel`) with ReLU activations, softmax
  output, and backpropagation training, supporting:
  - A **context window** (N-gram) so it learns short multi-token patterns.
  - **Temperature-based sampling** for varied generation.
- **Vocabulary** builder that maps tokens to indices (reserves an `<unk>` token).
- **Embedded Tomcat** web server with a form for:
  - Interacting with the model (type a message, get a predicted reply).
  - Uploading training documents in **`.txt`, `.docx`, or `.pdf`** format.
- **Document persistence**: training documents live in the `data/` folder and
  are merged into the model on every startup, so your data survives restarts.

## Requirements

- JDK 26
- Gradle (the included wrapper `gradlew.bat` is used)

## Build & Run

```bash
./gradlew build
./gradlew run
```

Then open [http://localhost:8080/](http://localhost:8080/) in your browser.

## Usage

1. **Train**: upload a `.txt`, `.docx`, or `.pdf` document using the *Train*
   form. The document is extracted, merged with the existing corpus, and the
   model is re-trained. Vocabulary size is shown on the page.
2. **Interact**: type a message in the *Interact* box and press *Send*. Common
   greetings and small talk are answered by the rule layer; anything else falls
   through to the MLP.

## Model configuration

The MLP is configurable via system properties:

| Property                    | Default | Description                 |
|-----------------------------|---------|-----------------------------|
| `campanionai.hiddenSize`    | `512`   | Hidden layer size           |
| `campanionai.contextWindow` | `5`     | Number of context tokens    |
| `campanionai.temperature`   | `0.8`   | Sampling temperature        |

Example: `./gradlew run -Dcampanionai.hiddenSize=1024`

## Project layout

```
src/main/java/davejones74/campanionai/
├── Main.java              # Simple entry point
├── Server.java            # Starts embedded Tomcat
├── ModelServlet.java      # HTTP handler: form, upload, training, replies
├── ChatRules.java         # Rule-based conversational layer
├── DocumentReader.java    # Extracts text from .txt / .docx / .pdf
├── MlpLanguageModel.java  # MLP with context window + temperature sampling
└── Vocabulary.java        # Word <-> index mapping
src/main/resources/        # Reserved for application properties / config
data/                      # Training documents (created at runtime)
└── greetings.txt, exchanges.txt, formal.txt, casual.txt, farewells.txt,
    questions.txt          # Bundled greeting documents
```

## Notes

- This is a **learning project, not a production LLM**. It has two parts:
  1. A **rule engine** (`ChatRules`) that gives crisp, reliable replies for a
     fixed set of intents — this is what makes "hello → hello" work.
  2. An **MLP language model** that learns from uploaded documents but is a
     statistical next-token predictor: it has no real understanding or
     long-range reasoning, so its free-form output is often nonsense.
- The model is an MLP (Multi-Layer Perceptron) using a short context window;
  it is not a Transformer or a general-purpose language model.
- Training documents live in `data/`, which is excluded from version control
  via `.gitignore`. The bundled greeting documents live there too, so drop any
  `.txt/.docx/.pdf` file into `data/` and it is picked up automatically on
  restart.
