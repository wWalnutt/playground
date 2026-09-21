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

## PostgreSQL + pgvector (optional)

This is an independent local database for future vector search. The chat
application does not connect to it yet; no Embedding model or Spring AI
VectorStore is configured.

Start Docker Desktop (or your Docker daemon) first. Set `PGVECTOR_PASSWORD` in
your local environment, without storing it in source code. In macOS zsh:

```bash
read -s "PGVECTOR_PASSWORD?Database password: "; echo
export PGVECTOR_PASSWORD
docker-compose -f docker-compose.pgvector.yml up -d --wait
```

These commands use the standalone `docker-compose` installed locally. If using
the Docker Desktop Compose plugin, replace it with `docker compose`.

The container runs PostgreSQL 16 with pgvector 0.8.0. Connection settings:

| Setting | Default |
| --- | --- |
| Host | `127.0.0.1` (not exposed to the LAN) |
| Port | `5433` (override with `PGVECTOR_PORT`) |
| Database | `knowledge` (override with `PGVECTOR_DB`) |
| Username | `knowledge` (override with `PGVECTOR_USER`) |
| Password | Your `PGVECTOR_PASSWORD` |
| JDBC URL | `jdbc:postgresql://localhost:5433/knowledge` |

The initialization script `docker/pgvector/init.sql` enables the `vector`
extension. The health check waits for both PostgreSQL and the extension.
No vector table or index is created yet: the vector dimension depends on the
Embedding model we choose later.

Inspect the extension and try a vector distance calculation:

```bash
docker-compose -f docker-compose.pgvector.yml exec pgvector \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

```sql
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT '[1,0,0]'::vector <=> '[1,0,0]'::vector AS cosine_distance;
-- Expected: 0 (identical vectors).
```

Data persists in the `pgvector_data` named volume. Stop the database without
deleting its data:

```bash
docker-compose -f docker-compose.pgvector.yml down
```

Do not add `-v` unless you intend to delete the database. Initialization scripts
and `POSTGRES_*` settings apply only when the data directory is empty. Changing
the environment password later does not change an existing database password.
If reusing an existing data volume without the extension, run the initialization
SQL manually as a database administrator.

# kafka
docker-compose -f docker-compose.kafka.yml up -d
docker-compose -f docker-compose.kafka.yml down
docker-compose -f docker-compose.kafka.yml down -v