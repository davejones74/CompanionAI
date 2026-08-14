# CompanionAI

A small Java web application that trains a simple Multi-Layer Perceptron (MLP)
language model on greeting/English text and lets you interact with it in a
browser.

## Features

- **MLP language model** (`MlpLanguageModel`) with ReLU activations, softmax
  output, and backpropagation training.
- **Vocabulary** builder that maps tokens to indices.
- **Embedded Tomcat** web server with a form for:
  - Interacting with the model (type a message, get a predicted reply).
  - Uploading training documents in **`.txt`, `.docx`, or `.pdf`** format.
- **Document persistence**: uploaded documents are saved to the `data/` folder
  and merged into the model on every startup, so your training data survives
  restarts.

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
   model is re-trained. Current vocabulary size is shown on the page.
2. **Interact**: type a message in the *Interact* box and press *Send* to see
   the model's predicted reply.

## Project layout

```
src/main/java/davejones74/campanionai/
├── Main.java              # Simple entry point
├── Server.java            # Starts embedded Tomcat
├── ModelServlet.java      # HTTP handler: form, upload, training, replies
├── DocumentReader.java    # Extracts text from .txt / .docx / .pdf
├── MlpLanguageModel.java  # MLP with forward/backprop
└── Vocabulary.java        # Word <-> index mapping
src/main/resources/
└── greetings.txt          # Bundled default training document
data/                      # Uploaded documents (created at runtime)
```

## Notes

- This is a **bigram** model: it predicts the next token from a single previous
  token, so replies are simplistic. It is intended as a learning exercise and
  stepping stone, not a production LLM.
- Uploaded documents live in `data/`, which is excluded from version control
  via `.gitignore`.
