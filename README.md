# playground
a new project for learn

## 启动命令速查

以下命令都在项目根目录执行。使用本机的 `docker-compose`；如果安装的是
Docker Compose 插件，可替换为 `docker compose`。

### 1. 准备环境

先启动 Docker Desktop 并等待引擎就绪；使用 Colima 的话改为 `colima start`。

```bash
open -a Docker
docker info
```

数据库密码已保存在本地 `.env` 的 `PGVECTOR_PASSWORD` 中，无需重复设置。
该文件不提交 Git。DeepSeek Key 需要在启动应用的终端环境中设置；
如果尚未设置，可在 macOS zsh 中执行（输入不会显示）：

```zsh
read -s "DEEPSEEK_API_KEY?请输入 DeepSeek API Key: "; echo
export DEEPSEEK_API_KEY
```

### 2. 首次启动：下载模型并启用向量功能

按顺序执行，等待模型下载完成后再启动应用：

```bash
docker-compose -f docker-compose.pgvector.yml --profile embedding up -d --wait
docker-compose -f docker-compose.pgvector.yml exec -T ollama ollama pull bge-m3
./gradlew bootRun --args='--spring.profiles.active=vectors'
```

其中 `embedding` 是 Docker Compose 的服务分组，`vectors` 是 Spring 的配置分组，
两者不是同一个参数。模型下载后保存在 Docker 卷中，不需要每次重新下载。

### 3. 日常启动

```bash
docker-compose -f docker-compose.pgvector.yml --profile embedding up -d --wait
./gradlew bootRun --args='--spring.profiles.active=vectors'
```

打开聊天页面：<http://localhost:8080/>。向量化、入库和检索接口也已启用，
但聊天暂不自动检索知识库。

如果只需要 DeepSeek 聊天，不需要数据库和 Ollama：

```bash
./gradlew bootRun
```

两种应用启动方式任选一种，不要同时占用 `8080` 端口。

### 4. IDEA 启动

运行入口：`org.walnut.playground.springai.SpringAiApplicationKt`。
Working directory 设为项目根目录，Environment variables 设置 `DEEPSEEK_API_KEY`。
启用向量功能时，Program arguments 填写：

```text
--spring.profiles.active=vectors
```

IDEA 只启动应用；PostgreSQL 和 Ollama 仍需要先通过上面的 Compose 命令启动。
仅聊天时留空 Program arguments 即可。

### 5. 查看状态与停止

```bash
docker-compose -f docker-compose.pgvector.yml --profile embedding ps
docker-compose -f docker-compose.pgvector.yml exec -T ollama ollama list
```

终端启动的应用按 `Ctrl+C` 停止，IDEA 启动的应用点击 Stop。
停止两个容器并保留数据库和模型文件：

```bash
docker-compose -f docker-compose.pgvector.yml --profile embedding down
```

不要加 `-v`，否则会删除数据库和模型所在的数据卷。

## Spring AI

The only application entry point is
`org.walnut.playground.springai.SpringAiApplicationKt`.
It automatically activates the `springai` profile and scans only the Spring AI
package. Other demos remain available as ordinary functions, not application entry points.

See the startup command reference above for terminal and IDE instructions.
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

The database is used by the optional `vectors` Spring profile described below.
The default chat-only mode does not connect to it.

Use the startup command reference above. Compose reads `PGVECTOR_PASSWORD`
from the local `.env` file; do not commit this file or passwords.

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
The database container itself creates no tables. Starting the application with
the `vectors` profile creates `public.bge_m3_documents`, with `vector(1024)`
embeddings and an HNSW cosine-distance index.

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

Data persists in the `pgvector_data` named volume.
Do not add `-v` unless you intend to delete the database. Initialization scripts
and `POSTGRES_*` settings apply only when the data directory is empty. Changing
the environment password later does not change an existing database password.
If reusing an existing data volume without the extension, run the initialization
SQL manually as a database administrator.

## Local embeddings: Ollama + bge-m3

Chat still uses DeepSeek. Embedding and similarity search run locally using
Ollama's `bge-m3` (1024 dimensions) and PostgreSQL. These endpoints do not send
document contents to DeepSeek, and `/api/chat` does not use retrieved documents
yet. The frontend chat behavior is unchanged.

Use the startup command reference above to download the model and enable the
`vectors` profile.

The first model download is large (roughly 1.2 GB). Model files persist in the
`ollama_data` volume. Docker on macOS normally runs inference on CPU; a native
Ollama installation can use Apple GPU acceleration. To use native Ollama instead,
leave the Docker Ollama service stopped, run `ollama pull bge-m3` against your
local Ollama server, and keep the same application configuration.

Ollama must be listening on `http://localhost:11434`; override `OLLAMA_BASE_URL`
if needed. The application will not download models automatically. `vectors`
imports the local root `.env` as properties for `PGVECTOR_PASSWORD` (run the app
from the repository root); exported environment variables take precedence.
In the IDE, add `--spring.profiles.active=vectors` to program arguments.

Inspect a vector without storing anything (up to 2,000 characters):

```bash
curl http://localhost:8080/api/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"text":"When must travel expenses be submitted?"}'
```

Response: `{"model":"bge-m3","dimensions":1024,"vector":[...]}`.

Index text (up to 20,000 characters per request):

```bash
curl http://localhost:8080/api/knowledge/documents \
  -H 'Content-Type: application/json' \
  -d '{"text":"Travel expenses must be submitted within 30 days of returning.","source":"travel-policy"}'
```

The pipeline splits text into approximately 500-token chunks, embeds them, and
stores the original text plus source, document ID, model, and chunk index.
The response contains `documentId` and `chunksIndexed`. Each upload creates new
records; uploading the same text again is not deduplicated.

Search without invoking DeepSeek:

```bash
curl http://localhost:8080/api/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"What is the deadline for travel reimbursement?","topK":3}'
```

Results contain chunk ID, original text, metadata, and similarity score (higher
is more similar). `topK` must be between 1 and 20; no matches returns `[]`.
This retrieves nearest neighbors, not a guarantee that a matching answer exists.
Blank or oversized inputs return HTTP 400; dependency failures are not hidden.

In IDEA's SQL console:

```sql
SELECT id, content, metadata, vector_dims(embedding) AS dimensions
FROM bge_m3_documents;
```

Do not mix different embedding models in this table. Changing models requires
a separate compatible table and re-embedding the documents; the earlier
three-dimensional `vector_demo` table is not used.

# kafka
docker-compose -f docker-compose.kafka.yml up -d
docker-compose -f docker-compose.kafka.yml down
docker-compose -f docker-compose.kafka.yml down -v