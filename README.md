# playground
a new project for learn

## Spring AI

The only application entry point is
`org.walnut.playground.springai.SpringAiApplicationKt`.
It automatically activates the `springai` profile and scans only the Spring AI
package. Other demos remain available as ordinary functions, not application entry points.

Set `DEEPSEEK_API_KEY` in your local environment and run:

```bash
./gradlew bootRun
```

In the IDE, run `SpringAiApplicationKt` with the same environment variable.
The application calls DeepSeek directly; no Docker or database is required.

Open `http://localhost:8080/` for the chat UI. Enter sends a message; Shift+Enter
adds a newline. Failed requests can be retried. The page keeps messages only
until refresh and sends only the current message to DeepSeek, not the history.
The API key stays on the server and must not be placed in frontend files.

```bash
curl http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello, please introduce yourself."}'
```

The response is `{"reply":"..."}`. Each request is an independent conversation
(no history or document retrieval). Blank messages return HTTP 400.
Provider errors propagate; an empty model reply returns HTTP 502.

# kafka
docker-compose -f docker-compose.kafka.yml up -d
docker-compose -f docker-compose.kafka.yml down
docker-compose -f docker-compose.kafka.yml down -v