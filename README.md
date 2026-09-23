# playground
a new project for learn

## 启动命令速查

### tmuxinator 一键启动（推荐）

先启动 Docker Desktop，并在当前终端设置 `DEEPSEEK_API_KEY`。
数据库密码继续从项目根目录 `.env` 读取。停止 IDEA 或其他终端中占用
`8080` 的应用，再执行：

```bash
./run standalone_tmux
```

与 customer-order 项目使用相同的方式：`./run standalone_tmux` 转发到
`tooling/runcommands/run_standalone_tmux.sh`，入口脚本调用
`tmuxinator start -p .tmuxinator_default.yml`，缺少 tmuxinator 时通过 Homebrew 安装。
首次创建会话前启动 PostgreSQL 和 Ollama，等待健康检查通过并确认 `bge-m3` 可用。
会话名为 `playground-ai`，只有一个 `main` 窗口，内部平铺五个 pane：

| Pane | 用途 |
| --- | --- |
| `backend` | 运行带 `vectors` 配置的 Spring Boot，显示应用日志 |
| `pgvector` | 查看 PostgreSQL / pgvector 容器日志 |
| `ollama` | 查看 Ollama / bge-m3 容器日志 |
| `shell` | 项目根目录的普通终端，可执行 curl 等命令 |
| `control` | 输入 `1` 并确认 `y` 关闭当前项目会话；输入 `2` 暂时离开 |

应用启动成功后访问 <http://localhost:8080/>。命令退出后保留 pane 和终端，
便于查看错误输出或重新执行。重复执行入口会进入已有会话，不会重复启动应用。
启动脚本直接使用本机的 `docker-compose`。控制菜单使用 Bash，不依赖额外菜单工具。

鼠标点击切换 pane，也可以 `Ctrl+b` 后按方向键。
`Ctrl+b` 后按 `z` 放大/还原当前 pane，按 `d` 暂时离开会话（应用继续运行）。
也可以只在后台创建会话：

```bash
./run standalone_tmux --no-attach
```

重新连接：

```bash
tmux attach-session -t playground-ai
```

布局在 `.tmuxinator_default.yml` 中，鼠标和 pane 标题配置在
`tooling/local-standalone/tmux.conf` 中，沿用默认 tmux server。
若 tmux server 早于环境变量启动，建议将 `DEEPSEEK_API_KEY` 设置在本地
`.env` 中（`vectors` 配置会读取，不提交 Git），然后重启 backend。
在已有 tmux 中可使用 `--no-attach` 创建会话，再切换到该会话。

可以在 `control` pane 输入 `1`，再输入 `y` 确认关闭当前项目会话和应用，
不会关闭其他 tmux 会话。输入 `2` 只离开会话，应用继续运行。
也可以先在 `backend` pane 按 `Ctrl+C`，然后在普通终端执行：

```bash
tmux kill-session -t playground-ai
docker-compose -f docker-compose.pgvector.yml --profile embedding down
```

结束 tmux 会话不会自动停止 Docker 容器。停止容器时不要加 `-v`，
以保留数据库和模型文件。下面保留手动启动步骤供排查和首次配置使用。

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

打开聊天页面：<http://localhost:8080/>。左侧有“DS 正常对话”“RAG 对话”
和“上传参考文档”三个入口，手机端显示为顶部导航。
正常对话直接调用 DeepSeek；RAG 先检索知识库，并在回复下方展示可展开的来源。
RAG 回复还提供“检索诊断”，显示实际阈值、候选与过滤数量、相似度、
检索耗时及是否调用 DeepSeek。
上传页支持直接输入资料名称和正文，也支持选择 TXT/Markdown 文件。
页面下方的“知识库文档”支持分页浏览、查看分块原文和确认删除；上传后自动刷新列表。

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
adds a newline. Replies arrive incrementally; “停止生成” cancels the current request.
Interrupted replies retain their partial text and are explicitly marked incomplete.
“重新生成” starts a new request with the original question and mode, not a continuation.
The page keeps messages only
until refresh and sends only the current message to DeepSeek, not the history.
The API key stays on the server and must not be placed in frontend files.
The sidebar defaults to “DS 正常对话” (`/api/chat/stream`). “RAG 对话” calls
`/api/rag/chat/stream` with `topK: 3`, requires the `vectors` profile, and displays
threshold-filtered sources and retrieval diagnostics below the answer. Each message is labeled with its mode;
retries use the original mode even after switching. Both modes are single-turn.
The two chat views keep separate message lists and input drafts in the page;
switching views does not erase them, but refreshing does.

Select “上传参考文档” in the sidebar. Under “直接输入文本”, enter a source name
(up to 200 characters) and text (up to 20,000 characters), then click “文本入库”;
this calls `/api/knowledge/documents`. Alternatively, select a TXT or Markdown
file under “上传文件” and click “上传并入库”. Both methods show the document ID
and indexed chunk count. Then select “RAG 对话” to ask about the material.
Uploads require the `vectors` profile and running PostgreSQL/Ollama.
While a request is running, navigation and further submissions are disabled
to avoid accidental concurrent requests. Upload drafts remain intact on error.

```bash
curl http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello, please introduce yourself."}'
```

The response is `{"reply":"..."}`. Each request is an independent conversation
(no history or document retrieval). Blank messages return HTTP 400.
Provider errors propagate; an empty model reply returns HTTP 502.

### 流式聊天（SSE）

页面默认通过 `fetch` 发送 POST 并持续读取 SSE 响应，不是收到完整答案后模拟打字。
普通聊天和 RAG 分别使用下面的接口，原有 `/api/chat`、`/api/rag/chat`
完整 JSON 接口保留，调用方可以继续使用。

```bash
curl -N http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"message":"解释一下 Kotlin 的空安全"}'

curl -N http://localhost:8080/api/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"question":"Kafka只有6个分区，启动8个消费者能加速吗？","topK":3}'
```

请求字段与对应 JSON 接口相同，RAG 同样支持 `similarityThreshold`。
响应类型为 `text/event-stream`，每个事件的 `data` 都是 JSON 对象：

| 事件 | 数据 | 含义 |
| --- | --- | --- |
| `status` | `{"stage":"retrieving"}` 或 `{"stage":"generating"}` | 当前处理阶段 |
| `sources` | `{"sources":[...],"diagnostics":{...}}` | RAG 检索结果，正文之前发送 |
| `delta` | `{"text":"一段增量文字"}` | 追加到同一个回答气泡 |
| `done` | `{"modelCalled":true}` | 本次回答完整结束；无资料拒答为 `false` |
| `error` | `{"code":"...","message":"..."}` | 本次失败，不再发送成功的 `done` |

例如，正文中的换行会编码在 JSON 字符串内，不会被误当作 SSE 事件边界。
客户端必须处理网络分片和 UTF-8 多字节字符，不能假定一次网络读取就是一个完整事件。
只有收到有效的 `done` 才能标记完成，连接关闭本身不代表回答完整。
后端每 5 秒发送 `:keep-alive` 注释心跳，客户端忽略其内容；
检测到写入失败时取消上游连接。总处理期限为 120 秒，包含 RAG 检索，
浏览器等待上限为 125 秒，以留出接收服务端超时事件的时间。
错误代码包括 `timeout`、`incomplete_response`、`empty_response` 和 `stream_failed`。
模型响应必须正常以 `stop` 结束且含有非空白正文；达到长度限制或异常中断不会被标记成功。

RAG 仍先完成检索和阈值过滤，再调用 DeepSeek 流式生成。
没有合格资料时发送来源/诊断、资料不足的提示和 `done(false)`，不调用 DeepSeek。
来源提前展示时，页面显示“生成尚未完成”，不会提前宣称模型已完成调用。
普通聊天不访问知识库，两个模式都不发送历史聊天记录。

点击停止会取消浏览器请求并触发后端取消上游订阅；网络断开被服务端检测到后同样清理。
这不保证提供方已产生的工作或费用可以撤销。
超时、模型中途失败、异常结束及未收到完成事件的断流，都保留部分文字并标记未完成，
不自动重试、不静默退回完整响应接口。重试会新增回答，保留原来的不完整记录。
参数错误仍使用 HTTP 错误；SSE 已开始后发生的故障通过 `error` 事件告知，
不能只检查最初的 HTTP 200。

如果前面部署反向代理，需要关闭该路径的响应缓冲，并合理设置读超时，否则可能
仍然看到整段内容最后一起出现。`curl -N` 用于关闭 curl 自身的输出缓冲。
前端流式解析和交互用例可运行 `node --test src/test/javascript/chat-stream.test.cjs`；
后端用例沿用 `./gradlew test --tests 'org.walnut.playground.springai.*'`。

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
document contents to DeepSeek. `/api/chat` remains plain chat. The separate
`/api/rag/chat` endpoint, also used by the frontend's RAG mode, does send
retrieved passages to DeepSeek.

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

### Upload a reference file

The frontend uploads files to `POST /api/knowledge/documents/upload` as
`multipart/form-data`, using the `file` field. You can also call it directly:

```bash
curl http://localhost:8080/api/knowledge/documents/upload \
  -F 'file=@/path/to/policy.md'
```

Supported extensions are `.txt`, `.md`, and `.markdown` (case-insensitive).
Files must be UTF-8 encoded (an optional UTF-8 BOM is accepted), nonempty,
at most 64 KiB, and contain at most 20,000 characters. Filenames must be at most
200 characters. PDF/Word are not supported; renaming them does not convert them.
Invalid content returns HTTP 400, unsupported extensions 415, oversized uploads
413. Files are treated as plain text, including Markdown; content is not
rendered as HTML or saved using client-supplied filesystem paths.

The backend uses the same chunking and indexing pipeline as the text endpoint.
It records the basename as `metadata.source` and returns `documentId` plus
`chunksIndexed`. Only extracted text chunks, vectors, and metadata are retained
in the database, not a downloadable copy of the original file.
The knowledge base is shared across conversations and persists after refresh.
No deduplication or automatic upload retry is performed. If a request times out,
the server may still be indexing or may already have finished. Refresh the
document list and check its status before resubmitting.
Ingestion is local; subsequent RAG requests send relevant passages to DeepSeek,
so upload only documents permitted to be shared with that service.

### 知识库文档管理

在“上传参考文档”页面下方查看文档列表，每页 10 篇，展示名称、文档 ID、
上传方式、上传时间、分块数量和入库状态。进入页面、上传完成或删除后会刷新列表，
也可以点击“刷新列表”查看其他客户端上传的资料或仍在处理的任务。
“查看分块”展示实际保存的文本块；内容仅按纯文本显示，不执行 HTML 或 Markdown。

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| `GET` | `/api/knowledge/documents?page=0&size=10` | 分页列表，页码从 0 开始 |
| `GET` | `/api/knowledge/documents/{documentId}` | 文档信息和按序排列的分块 |
| `DELETE` | `/api/knowledge/documents/{documentId}` | 删除文档及全部对应向量，成功返回 204 |

`size` 默认为 10，允许 1–100；`page` 必须是非负整数。
列表响应包含 `items`、`page`、`size`、`totalElements`、`totalPages`。
每个文档包含 `documentId`、`source`、`uploadType`、`uploadedAt`、
`chunksIndexed`、`status`；详情响应为 `{document, chunks}`，
每个分块包含 `id`、`chunkIndex`（从 0 开始）、`text`。

状态分别为 `PROCESSING`（正在入库）、`READY`（成功）、`FAILED`（失败）、
`LEGACY`（历史文档，无法确认原始入库完整性）。
上传方式为 `TEXT`、`FILE` 或 `LEGACY`。历史记录无法还原上传时间，
因此 `uploadedAt` 为 `null`，页面显示“未知”，不会用迁移时间代替。

启用 `vectors` 后，应用自动创建 `public.bge_m3_documents_management` 管理表，
并根据现有向量元数据补齐历史文档记录，重复启动不会重复导入。
表名跟随配置的向量表，格式为 `<向量表名>_management`，使用同一 schema。
分块数量从实际向量记录统计，避免管理记录与实际入库数量不一致。

入库开始时先保存 `PROCESSING`；全部向量写入与 `READY` 状态在同一事务提交。
失败时回滚本次向量写入，保留 `FAILED` 记录并返回错误。
应用启动或读取列表、详情时会识别中断的任务并标记失败；
跨实例数据库锁保护仍在执行的入库任务，不会将其误判为失败。
该事务流程要求数据库连接池至少有 2 个连接（默认 Hikari 配置满足）。

删除前页面要求确认。删除影响共享知识库中的这份文档及其全部分块，
不会删除其他同名文档，也不会清除已有聊天记录；已发出的 RAG 请求可能仍持有检索结果。
正在入库的文档不能删除（409），不存在的文档返回 404，非法参数返回 400。
删除失败或连接中断时应先刷新列表确认状态，而不是盲目重试。

这些管理接口仅在 `vectors` 配置启用时可用，只访问 PostgreSQL，
不会调用 DeepSeek 或 Ollama。不保存原文件，不提供原文件下载、在线编辑或自动去重。
当前知识库没有用户隔离和鉴权，仅适用于可信的本地环境。

仓库的 PostgreSQL 集成测试默认跳过。设置 `KNOWLEDGE_TEST_POSTGRES=true`、
`KNOWLEDGE_TEST_JDBC_URL`、`KNOWLEDGE_TEST_USER`、`KNOWLEDGE_TEST_PASSWORD` 后，
执行 `./gradlew test --tests '*KnowledgeDocumentRepositoryTests'` 可运行；
测试数据库需已启用 pgvector，账号需可创建 schema。测试仅使用随机新建的 schema，
结束后清理，不读写已有知识库文档。

### Similarity search

Search without invoking DeepSeek:

```bash
curl http://localhost:8080/api/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"What is the deadline for travel reimbursement?","topK":3}'
```

Results contain chunk ID, original text, metadata, and similarity score (higher
is more similar). `topK` must be between 1 and 20; no matches returns `[]`.
The shared retrieval service fetches at most `topK` baseline candidates, then
retains nonblank passages with scores greater than or equal to the threshold.
This is not a guarantee that a matching answer exists.
Blank or oversized inputs return HTTP 400; dependency failures are not hidden.

### 统一检索、阈值和诊断

`/api/knowledge/search`、`/api/knowledge/search/diagnostics` 和 `/api/rag/chat`
使用同一个检索服务。现有 search 接口继续返回数组；新的诊断接口返回
`{matches, diagnostics}`，不会调用 DeepSeek。

```bash
curl http://localhost:8080/api/knowledge/search/diagnostics \
  -H 'Content-Type: application/json' \
  -d '{"query":"Kafka只有6个分区，启动8个消费者能加速吗？","topK":3}'
```

默认阈值为 **0.46**，由本项目当前 bge-m3 知识库的小规模评估选择。
可以通过 `RETRIEVAL_SIMILARITY_THRESHOLD` 环境变量（或本地 `.env`）设置，
对应配置为 `app.retrieval.similarity-threshold`，重启后生效。
必须是 `[0,1]` 内的有限数值，错误配置会导致启动失败。

上述三个接口均接受可选的 `similarityThreshold`。省略或 `null` 使用服务端配置；
显式传 `0` 恢复未做正阈值过滤的基线，用于对比，而不是禁用检索。
阈值在服务端筛选，边界采用 `score >= threshold`；空文本仍然过滤。
前端不覆盖阈值，而是展示本次实际使用的值。非法请求参数返回 400，
向量服务返回缺失或非有限分数时返回 502；数据库或模型故障不会伪装成无结果。

`diagnostics` 包含 `topK`、`similarityThreshold`、`candidateCount`、
`acceptedCount`、`rejectedCount`、`elapsedMs` 和 `candidates`。
每个候选包含分块 ID `chunkId`、来源 `source`、分数 `score`、
`accepted`，以及 `ACCEPTED`、`BELOW_THRESHOLD` 或 `EMPTY_TEXT` 原因。
被过滤候选只提供诊断元数据，不作为生成上下文发送给 DeepSeek。
候选数量只指本次 top-K 池，不是全库相关文档数量；不会为凑够数量补搜。
耗时包含查询向量化、向量检索与筛选，不包含 DeepSeek 生成。
相似度不是正确率，主题相关也不代表材料包含问题要求的具体答案。

### 固定检索评估

`tooling/retrieval/evaluation-cases.json` 固定了 60 个手工标注的问题，涵盖直接提问、
同义改写、多来源问题、主题接近但缺少答案、完全无关问题。
评估基于当前共享知识库，**不写入文档、不调用 DeepSeek，只访问本机接口**。
按资料名称解析标注，若所需文档缺失、重名或仍在入库，会显式中止。
报告只保留候选标识、来源、分数与统计，不保存检索出的正文；
语料指纹包含文档与分块内容哈希，以检测评估期间或复验前的资料变化。

```bash
# 收集零阈值基线，按开发集选择阈值，再报告独立确认集表现
node tooling/retrieval/evaluate.mjs \
  --base-url http://localhost:8080 \
  --output tooling/retrieval/evaluation-report.json

# 后端重启并配置为报告所选阈值后，复验两个搜索接口与默认配置
node tooling/retrieval/evaluate.mjs \
  --base-url http://localhost:8080 \
  --verify-report tooling/retrieval/evaluation-report.json
```

脚本不自动改写服务端配置。第一条命令显式请求阈值 `0`，避免把已经过滤的结果
当成基线；第二条命令比较新诊断接口、旧数组接口及逐候选过滤原因，
并检查实际默认阈值和报告一致。检索分数或语料漂移时会停止，不能直接沿用旧结论。
新增资料或更换 embedding 模型后应重新评估；反复据确认集调参后，
该集合就不再是独立验证数据，需要另外准备未参与调参的问题。

本次先尝试了 `0.55`，在原验证集误拒 1 道有答案问题，因此未采用。
该失败记录保留在 `evaluation-attempt-01.json`；原 40 题明确转为开发数据，
增加 20 题新的 `confirmation` 集合。选择过程保留开发集原有证据命中，
并在最弱已命中的标注来源分数下预留 `0.05` 余量，得到 `0.46`。
新的确认集不参与阈值选择。

| 新确认集指标（20 题） | 零阈值基线 | 阈值 0.46 |
| --- | --- | --- |
| 有答案问题命中标注来源 | 14/14 | 14/14 |
| 标注来源覆盖数量（含多来源题） | 16/16 | 16/16 |
| 有答案问题被完全拒绝 | 0/14 | 0/14 |
| 无答案问题仍有资料通过 | 6/6 | 3/6 |
| 完全无关问题仍有资料通过 | 3/3 | 0/3 |
| 主题接近但缺少答案仍有资料通过 | 3/3 | 3/3 |

这证明了**在该小样本确认集上**减少无关资料进入生成步骤，且没有新增漏检，
不是回答准确率达到 100% 的证明。原开发集仍有基线未找到标注资料的问题；
阈值不会改善排序，也无法可靠判断高相似度材料是否足以回答具体问题。
报告保存单次检索耗时，但不能把不同运行间缓存和负载造成的变化当成性能提升。
当前不增加混合检索、重排序或新的模型依赖。

In IDEA's SQL console:

```sql
SELECT id, content, metadata, vector_dims(embedding) AS dimensions
FROM bge_m3_documents;
```

Do not mix different embedding models in this table. Changing models requires
a separate compatible table and re-embedding the documents; the earlier
three-dimensional `vector_demo` table is not used.

## RAG chat

With the `vectors` profile enabled and documents already indexed, call:

```bash
curl http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"出差回来多久要申请报销？","topK":3}'
```

The backend reuses knowledge search, supplies retrieved text to DeepSeek, and
returns `answer`, `sources`, `diagnostics`, and `modelCalled`. `question` must contain 1 to 2,000 characters
and cannot be blank. `topK` defaults to 3 and accepts 1 to 20.

Each source contains `reference` (the number used in `[1]` citations), `chunkId`,
the original `text`, original `metadata` (including `source` and `documentId`),
and similarity `score`. Sources are the actual passages supplied to the model,
not a model-generated source list or proof that every claim is supported.

If no usable passages pass the threshold, the endpoint returns an explicit
insufficient-information answer with `sources: []` and `modelCalled: false`,
without calling DeepSeek. This does not assert that the entire knowledge base
contains no answer. Successful generation returns `modelCalled: true`.
When passages are found, the prompt instructs the model to use only those
passages, cite them, and admit when they are insufficient. Retrieval currently
uses the shared top-K pool and threshold filter; these instructions do not
guarantee factual accuracy or correct inline citations. Source text is treated
as untrusted data rather than system instructions.

This endpoint sends the question and retrieved document text to DeepSeek's
hosted API. Only use documents permitted to be shared with that service.
Provider or database failures propagate as errors rather than being reported as
"no information"; an empty model reply returns HTTP 502.
The existing `/api/chat` remains unchanged; neither mode sends chat history.

# kafka
docker-compose -f docker-compose.kafka.yml up -d
docker-compose -f docker-compose.kafka.yml down
docker-compose -f docker-compose.kafka.yml down -v