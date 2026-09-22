"use strict";

const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const messageLists = {
  chat: document.querySelector("#messages"),
  rag: document.querySelector("#rag-messages"),
};
const sendButton = document.querySelector("#send-button");
const status = document.querySelector("#request-status");
const navigation = document.querySelectorAll("[data-view]");
const viewTitle = document.querySelector("#view-title");
const viewDescription = document.querySelector("#view-description");
const referencePanel = document.querySelector("#reference-panel");
const modeHint = document.querySelector("#mode-hint");
const modeNames = { chat: "DS 正常对话", rag: "RAG 对话", upload: "上传参考文档" };
const uploadForm = document.querySelector("#upload-form");
const fileInput = document.querySelector("#document-file");
const uploadButton = document.querySelector("#upload-button");
const uploadStatus = document.querySelector("#upload-status");
const textUploadForm = document.querySelector("#text-upload-form");
const sourceInput = document.querySelector("#document-source");
const textInput = document.querySelector("#document-text");
const textUploadButton = document.querySelector("#text-upload-button");
const textUploadStatus = document.querySelector("#text-upload-status");
const textCount = document.querySelector("#text-count");
const documentManager = document.querySelector("#document-manager");
const documentList = document.querySelector("#document-list");
const documentsStatus = document.querySelector("#documents-status");
const documentsRefresh = document.querySelector("#documents-refresh");
const documentsPrevious = document.querySelector("#documents-previous");
const documentsNext = document.querySelector("#documents-next");
const documentsPageLabel = document.querySelector("#documents-page");
const documentDetail = document.querySelector("#document-detail");
const documentDetailTitle = document.querySelector("#document-detail-title");
const documentDetailMeta = document.querySelector("#document-detail-meta");
const documentChunks = document.querySelector("#document-chunks");
const documentDetailClose = document.querySelector("#document-detail-close");
const documentStatuses = { PROCESSING: "正在入库", READY: "入库成功", FAILED: "入库失败", LEGACY: "历史文档（完整性未知）" };
const uploadTypes = { TEXT: "直接输入", FILE: "文件上传", LEGACY: "历史导入" };
const drafts = { chat: "", rag: "" };
let activeView = "chat";
let sending = false;
let uploading = "";
let managing = false;
let documentsPage = 0;
let documentsTotalPages = 0;

function updateControls() {
  const busy = sending || uploading !== "" || managing;
  sendButton.disabled = busy || activeView === "upload" || !input.value.trim();
  sendButton.textContent = sending ? "等待回复…" : "发送";
  navigation.forEach((button) => { button.disabled = busy; });
  fileInput.disabled = busy;
  uploadButton.disabled = busy || !fileInput.files.length;
  uploadButton.textContent = uploading === "file" ? "正在入库…" : "上传并入库";
  sourceInput.disabled = busy;
  textInput.disabled = busy;
  textUploadButton.disabled = busy || !sourceInput.value.trim() || !textInput.value.trim();
  textUploadButton.textContent = uploading === "text" ? "正在入库…" : "文本入库";
  textCount.textContent = `${textInput.value.length.toLocaleString()} / 20,000 字符`;
  Object.values(messageLists).forEach((messages) => {
    messages.querySelectorAll(".retry-button").forEach((button) => { button.disabled = busy; });
  });
  documentManager.setAttribute("aria-busy", String(managing));
  documentsRefresh.disabled = busy;
  documentsPrevious.disabled = busy || documentsTotalPages === 0 || documentsPage <= 0;
  documentsNext.disabled = busy || documentsPage + 1 >= documentsTotalPages;
  documentDetailClose.disabled = busy;
  documentList.querySelectorAll("button").forEach((button) => {
    button.disabled = busy || button.dataset.processing === "true";
  });
}

function scrollToLatest(mode = activeView) {
  const messages = messageLists[mode];
  if (messages) messages.scrollTop = messages.scrollHeight;
}

function switchView(view) {
  if (sending || uploading || managing || view === activeView) return;
  if (activeView !== "upload") drafts[activeView] = input.value;
  activeView = view;
  navigation.forEach((button) => {
    if (button.dataset.view === view) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
  });
  Object.entries(messageLists).forEach(([mode, messages]) => { messages.hidden = mode !== view; });
  form.hidden = view === "upload";
  referencePanel.hidden = view !== "upload";
  viewTitle.textContent = modeNames[view];
  viewDescription.textContent = {
    chat: "直接与 DeepSeek 对话，不检索知识库",
    rag: "先检索参考资料，再根据资料回答",
    upload: "添加参考资料，查看和管理知识库文档",
  }[view];
  modeHint.textContent = {
    chat: "普通聊天：直接调用 DeepSeek，不检索知识库。",
    rag: "RAG 对话：最多检索 3 条候选资料，再按相似度阈值筛选（问题最多 2000 字符）。通过筛选的原文会发送给 DeepSeek。",
    upload: "两种方式都将资料分块、向量化并存入知识库。需要启用 vectors 配置，并运行 PostgreSQL 和 Ollama。",
  }[view];
  input.value = view === "upload" ? "" : drafts[view];
  input.setCustomValidity("");
  status.textContent = "";
  updateControls();
  scrollToLatest();
  if (view === "upload") void refreshDocuments();
}

function appendMessage(role, text, mode) {
  const row = document.createElement("article");
  row.className = `message ${role}`;
  const avatar = document.createElement("div");
  avatar.className = `avatar ${role}-avatar`;
  avatar.setAttribute("aria-hidden", "true");
  avatar.textContent = role === "user" ? "我" : "D";
  const body = document.createElement("div");
  body.className = "message-body";
  const label = document.createElement("span");
  label.className = "message-label";
  label.textContent = `${role === "user" ? "我" : "助手"} · ${modeNames[mode]}`;
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  // Treat both user input and model output as text, never executable markup.
  bubble.textContent = text;
  body.append(label, bubble);
  row.append(avatar, body);
  messageLists[mode].append(row);
  scrollToLatest(mode);
  return { row, bubble, body };
}

function renderSources(body, sources) {
  if (!sources.length) {
    const note = document.createElement("p");
    note.className = "sources-note";
    note.textContent = "本次没有达到阈值的可用资料，未调用 DeepSeek；不代表知识库一定没有答案。";
    body.append(note);
    return;
  }
  const details = document.createElement("details");
  details.className = "sources";
  const summary = document.createElement("summary");
  summary.textContent = `查看检索来源（${sources.length}）`;
  const note = document.createElement("p");
  note.className = "sources-note";
  note.textContent = "以下资料已提供给模型，不代表回答中的每个结论都已核实。";
  details.append(summary, note);
  sources.forEach((source) => {
    const item = document.createElement("section");
    item.className = "source-item";
    const title = document.createElement("strong");
    title.textContent = `[${source.reference}] ${typeof source.metadata?.source === "string" ? source.metadata.source : "未命名来源"}`;
    const metadata = document.createElement("p");
    metadata.className = "source-meta";
    metadata.textContent = `分块 ID：${source.chunkId}`;
    if (typeof source.metadata?.documentId === "string") {
      metadata.textContent += ` · 文档 ID：${source.metadata.documentId}`;
    }
    if (Number.isFinite(source.score)) {
      metadata.textContent += ` · 相似度：${source.score.toFixed(3)}`;
    }
    const text = document.createElement("p");
    text.className = "source-text";
    text.textContent = source.text;
    item.append(title, metadata, text);
    details.append(item);
  });
  body.append(details);
}

function validRetrievalDiagnostics(data) {
  const diagnostic = data.diagnostics;
  const reasons = ["ACCEPTED", "BELOW_THRESHOLD", "EMPTY_TEXT"];
  return diagnostic &&
    Number.isInteger(diagnostic.topK) && diagnostic.topK >= 1 && diagnostic.topK <= 20 &&
    Number.isFinite(diagnostic.similarityThreshold) && diagnostic.similarityThreshold >= 0 && diagnostic.similarityThreshold <= 1 &&
    Number.isInteger(diagnostic.candidateCount) && diagnostic.candidateCount >= 0 && diagnostic.candidateCount <= diagnostic.topK &&
    Number.isInteger(diagnostic.acceptedCount) && diagnostic.acceptedCount === data.sources.length &&
    Number.isInteger(diagnostic.rejectedCount) && diagnostic.rejectedCount >= 0 &&
    diagnostic.acceptedCount + diagnostic.rejectedCount === diagnostic.candidateCount &&
    Number.isSafeInteger(diagnostic.elapsedMs) && diagnostic.elapsedMs >= 0 &&
    typeof data.modelCalled === "boolean" && data.modelCalled === (data.sources.length > 0) &&
    Array.isArray(diagnostic.candidates) && diagnostic.candidates.length === diagnostic.candidateCount &&
    diagnostic.candidates.every((candidate) =>
      candidate && typeof candidate.chunkId === "string" &&
      (candidate.source === null || typeof candidate.source === "string") &&
      Number.isFinite(candidate.score) && typeof candidate.accepted === "boolean" &&
      reasons.includes(candidate.reason) && candidate.accepted === (candidate.reason === "ACCEPTED") &&
      (!candidate.accepted || candidate.score >= diagnostic.similarityThreshold)) &&
    diagnostic.candidates.filter((candidate) => candidate.accepted).length === diagnostic.acceptedCount &&
    data.sources.every((source) => diagnostic.candidates.some((candidate) => candidate.accepted && candidate.chunkId === source.chunkId));
}

function renderRetrievalDiagnostics(body, diagnostic, modelCalled) {
  const details = document.createElement("details");
  details.className = "sources retrieval-diagnostics";
  const summary = document.createElement("summary");
  summary.textContent = `检索诊断 · 保留 ${diagnostic.acceptedCount}/${diagnostic.candidateCount} 条 · ${modelCalled ? "已调用 DeepSeek" : "未调用 DeepSeek"}`;
  const overview = document.createElement("p");
  overview.className = "source-meta";
  overview.textContent = `候选上限 ${diagnostic.topK} · 相似度阈值 ≥ ${diagnostic.similarityThreshold} · 过滤 ${diagnostic.rejectedCount} 条 · 检索耗时 ${diagnostic.elapsedMs} ms`;
  const note = document.createElement("p");
  note.className = "sources-note";
  note.textContent = "相似度不是正确率。候选数量仅指本次 top-K 结果，不是全库相关文档总数；耗时包含向量化与检索，不含 DeepSeek 生成。达到阈值仍不保证资料有答案。";
  details.append(summary, overview, note);
  const reasons = { ACCEPTED: "保留", BELOW_THRESHOLD: "低于阈值", EMPTY_TEXT: "空文本" };
  diagnostic.candidates.forEach((candidate) => {
    const item = document.createElement("p");
    item.className = "source-meta retrieval-candidate";
    item.dataset.accepted = String(candidate.accepted);
    item.textContent = `${reasons[candidate.reason]} · 相似度 ${candidate.score.toFixed(4)} · ${candidate.source ?? "未命名来源"}\n分块 ID：${candidate.chunkId}`;
    details.append(item);
  });
  if (!diagnostic.candidateCount) {
    const empty = document.createElement("p");
    empty.className = "sources-note";
    empty.textContent = "本次向量检索没有返回候选资料。";
    details.append(empty);
  }
  body.append(details);
}

async function send(message, mode, failedRow) {
  if (sending || uploading || managing) return;
  sending = true;
  if (failedRow) {
    failedRow.remove();
  } else {
    appendMessage("user", message, mode);
    input.value = "";
    drafts[mode] = "";
  }
  const pending = appendMessage("assistant", mode === "rag" ? "正在检索知识库并准备回答…" : "正在思考，请稍候…", mode);
  pending.row.classList.add("pending");
  status.textContent = mode === "rag" ? "正在检索资料并等待回答" : "正在等待 DeepSeek 回复";
  updateControls();

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 120000);
  try {
    const response = await fetch(mode === "rag" ? "/api/rag/chat" : "/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(mode === "rag" ? { question: message, topK: 3 } : { message }),
      signal: controller.signal,
    });
    if (!response.ok) {
      if (mode === "rag" && response.status === 404) {
        throw new Error("RAG 接口未启用，请用 --spring.profiles.active=vectors 重启后端，并确保 PostgreSQL 和 Ollama 已启动。");
      }
      throw new Error(`请求失败（HTTP ${response.status}），请检查服务配置后重试。`);
    }
    const data = await response.json();
    const reply = mode === "rag" ? data?.answer : data?.reply;
    if (typeof reply !== "string" || !reply.trim()) {
      throw new Error("服务没有返回有效回复，请重试。");
    }
    if (mode === "rag" && (!Array.isArray(data.sources) || !data.sources.every((source) =>
      source && Number.isInteger(source.reference) && source.reference > 0 &&
      typeof source.chunkId === "string" && typeof source.text === "string"))) {
      throw new Error("服务返回的引用来源格式不正确，请重试。");
    }
    if (mode === "rag" && !validRetrievalDiagnostics(data)) {
      throw new Error("检索诊断格式异常，请确认后端已更新并重启，不要将此结果当作有效回答。");
    }
    pending.bubble.textContent = reply;
    if (mode === "rag") {
      renderRetrievalDiagnostics(pending.body, data.diagnostics, data.modelCalled);
      renderSources(pending.body, data.sources);
    }
    status.textContent = "回复已收到";
  } catch (error) {
    pending.row.classList.add("failed");
    if (controller.signal.aborted) {
      pending.bubble.textContent = "等待回复超时，请稍后重试。";
    } else if (error instanceof TypeError) {
      pending.bubble.textContent = "无法连接聊天服务，请确认应用已启动，并检查网络。";
    } else if (error instanceof SyntaxError) {
      pending.bubble.textContent = "服务返回的数据格式不正确，请重试。";
    } else {
      pending.bubble.textContent = error instanceof Error ? error.message : "发送失败，请重试。";
    }
    const retry = document.createElement("button");
    retry.type = "button";
    retry.className = "retry-button";
    retry.textContent = "重新发送";
    retry.addEventListener("click", () => send(message, mode, pending.row));
    pending.bubble.append(retry);
    status.textContent = "发送失败";
  } finally {
    clearTimeout(timeout);
    sending = false;
    pending.row.classList.remove("pending");
    updateControls();
    scrollToLatest(mode);
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  if (activeView === "upload") return;
  const message = input.value.trim();
  if (activeView === "rag" && message.length > 2000) {
    input.setCustomValidity("RAG 问题不能超过 2000 个字符。");
    input.reportValidity();
    return;
  }
  if (message && !sending && !uploading && !managing) {
    void send(message, activeView);
  }
});

input.addEventListener("input", () => {
  input.setCustomValidity("");
  updateControls();
});
navigation.forEach((button) => {
  button.addEventListener("click", () => switchView(button.dataset.view));
});
input.addEventListener("keydown", (event) => {
  // Do not submit when Enter is used to confirm Chinese IME composition.
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    if (!sending && !uploading && !managing) form.requestSubmit();
  }
});

fileInput.addEventListener("change", () => {
  uploadStatus.textContent = "";
  uploadStatus.classList.remove("error");
  updateControls();
});

function showUploadError(element, message) {
  element.textContent = message;
  element.classList.add("error");
}

uploadForm.addEventListener("submit", (event) => {
  event.preventDefault();
  if (sending || uploading || managing) return;
  const file = fileInput.files[0];
  if (!file) {
    showUploadError(uploadStatus, "请先选择一个文件。");
    return;
  }
  if (!/\.(txt|md|markdown)$/i.test(file.name) || !file.size || file.size > 65536 || file.name.length > 200) {
    showUploadError(uploadStatus, "请选择非空的 TXT/Markdown 文件，大小不超过 64 KiB，文件名不超过 200 字符。");
    return;
  }
  const payload = new FormData();
  payload.append("file", file);
  return indexDocument({
    kind: "file",
    title: file.name,
    url: "/api/knowledge/documents/upload",
    options: { body: payload },
    output: uploadStatus,
    invalidMessage: "文件无效：请确认采用 UTF-8 编码、包含非空文本且正文不超过 20,000 字符。",
    onSuccess: () => { fileInput.value = ""; },
  });
});

textUploadForm.addEventListener("submit", (event) => {
  event.preventDefault();
  if (sending || uploading || managing) return;
  const source = sourceInput.value.trim();
  const text = textInput.value;
  if (!source || source.length > 200 || !text.trim() || text.length > 20000) {
    showUploadError(textUploadStatus, "请填写 1–200 字符的资料名称和非空正文，正文不能超过 20,000 字符。");
    return;
  }
  return indexDocument({
    kind: "text",
    title: source,
    url: "/api/knowledge/documents",
    options: { headers: { "Content-Type": "application/json" }, body: JSON.stringify({ text, source }) },
    output: textUploadStatus,
    invalidMessage: "文本无效：资料名称最多 200 字符，正文最多 20,000 字符，且不能为空。",
    onSuccess: () => { textInput.value = ""; },
  });
});

[sourceInput, textInput].forEach((element) => {
  element.addEventListener("input", () => {
    textUploadStatus.textContent = "";
    textUploadStatus.classList.remove("error");
    updateControls();
  });
});

async function indexDocument({ kind, title, url, options, output, invalidMessage, onSuccess }) {
  if (sending || uploading || managing) return;
  uploading = kind;
  output.classList.remove("error");
  output.textContent = `正在提交并向量化「${title}」，首次处理可能较慢，请勿重复提交…`;
  updateControls();
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 300000);
  try {
    const response = await fetch(url, {
      method: "POST",
      ...options,
      signal: controller.signal,
    });
    if (!response.ok) {
      const errors = {
        400: invalidMessage,
        404: "入库接口未启用，请用 --spring.profiles.active=vectors 重启后端。",
        413: "文件过大，请选择不超过 64 KiB 的文件。",
        415: "仅支持 TXT 和 Markdown 文件。",
      };
      throw new Error(errors[response.status] ||
        `入库失败（HTTP ${response.status}），请检查 PostgreSQL、Ollama 和后端日志，并刷新下方列表确认文档状态。`);
    }
    const result = await response.json();
    if (typeof result?.documentId !== "string" || !result.documentId ||
        !Number.isInteger(result.chunksIndexed) || result.chunksIndexed < 1) {
      throw new Error("服务返回了无效的入库结果，状态未确认，请先检查后端，避免重复上传。");
    }
    output.textContent = `「${title}」已入库，共 ${result.chunksIndexed} 个文本块。\n文档 ID：${result.documentId}\n选择左侧“RAG 对话”即可根据资料提问。`;
    onSuccess();
  } catch (error) {
    output.classList.add("error");
    if (controller.signal.aborted) {
      output.textContent = "等待入库超时，后端可能仍在处理。请先检查入库结果，避免重复上传。";
    } else if (error instanceof TypeError || error instanceof SyntaxError) {
      output.textContent = "连接中断或响应格式异常，入库结果未确认。请检查后端状态，避免重复上传。";
    } else {
      output.textContent = error instanceof Error ? error.message : "入库失败，请检查后端状态。";
    }
  } finally {
    clearTimeout(timeout);
    uploading = "";
    updateControls();
    await refreshDocuments(0);
  }
}

function validDocument(item) {
  return item && typeof item.documentId === "string" && item.documentId.length > 0 &&
    typeof item.source === "string" && Object.hasOwn(documentStatuses, item.status) &&
    Object.hasOwn(uploadTypes, item.uploadType) &&
    Number.isInteger(item.chunksIndexed) && item.chunksIndexed >= 0 &&
    (item.uploadedAt === null || (typeof item.uploadedAt === "string" && Number.isFinite(Date.parse(item.uploadedAt))));
}

function documentMetadata(item) {
  const uploadedAt = item.uploadedAt === null ? "未知" : new Date(item.uploadedAt).toLocaleString();
  return `文档 ID：${item.documentId}\n${uploadTypes[item.uploadType]} · ${item.chunksIndexed} 个文本块 · 上传时间：${uploadedAt}`;
}

async function documentRequest(path, method = "GET") {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 30000);
  try {
    const response = await fetch(`/api/knowledge/documents${path}`, { method, signal: controller.signal });
    if (!response.ok) {
      const errors = {
        400: "文档编号或分页参数无效，请刷新列表。",
        404: path.startsWith("?")
          ? "文档管理接口未启用，请用 --spring.profiles.active=vectors 重启后端。"
          : "文档不存在或接口未启用，请刷新列表并确认 vectors 配置已启用。",
        409: "文档正在入库，暂时不能删除，请稍后刷新列表。",
      };
      throw new Error(errors[response.status] || `文档操作失败（HTTP ${response.status}），请检查数据库和后端日志。`);
    }
    if (method === "DELETE") {
      if (response.status !== 204) throw new Error("删除响应异常，请刷新列表确认结果。");
      return;
    }
    return await response.json();
  } catch (error) {
    if (controller.signal.aborted || error instanceof TypeError || error instanceof SyntaxError) {
      throw new Error(method === "DELETE"
        ? "连接中断、超时或响应异常，删除结果未确认。请刷新列表，不要重复提交。"
        : "加载文档失败：连接中断、超时或响应异常。请检查后端后刷新列表。");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function manageDocuments(message, action) {
  if (sending || uploading || managing) return;
  managing = true;
  documentsStatus.classList.remove("error");
  documentsStatus.textContent = message;
  updateControls();
  try {
    await action();
  } catch (error) {
    if (documentsPageLabel.textContent === "加载中…") documentsPageLabel.textContent = "列表未加载";
    showUploadError(documentsStatus, error instanceof Error ? error.message : "文档操作失败，请刷新列表。");
  } finally {
    managing = false;
    updateControls();
  }
}

function renderDocuments(items) {
  documentList.replaceChildren();
  items.forEach((item) => {
    const row = document.createElement("article");
    row.className = "document-item";
    const title = document.createElement("h3");
    title.textContent = item.source;
    const state = document.createElement("span");
    state.className = "document-state";
    state.dataset.status = item.status;
    state.textContent = documentStatuses[item.status];
    const metadata = document.createElement("p");
    metadata.className = "document-meta";
    metadata.textContent = documentMetadata(item);
    const actions = document.createElement("div");
    actions.className = "document-actions";
    const detail = document.createElement("button");
    detail.type = "button";
    detail.className = "document-button";
    detail.textContent = "查看分块";
    detail.setAttribute("aria-label", `查看「${item.source}」的分块`);
    detail.addEventListener("click", () => showDocument(item.documentId));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "document-button document-delete";
    remove.dataset.processing = String(item.status === "PROCESSING");
    remove.textContent = item.status === "PROCESSING" ? "入库中，暂不可删除" : "删除文档";
    remove.setAttribute("aria-label", `删除「${item.source}」`);
    remove.addEventListener("click", () => deleteDocument(item));
    actions.append(detail, remove);
    row.append(title, state, metadata, actions);
    documentList.append(row);
  });
}

async function loadDocumentPage(page) {
  documentDetail.hidden = true;
  documentList.replaceChildren();
  documentsPageLabel.textContent = "加载中…";
  documentsTotalPages = 0;
  const result = await documentRequest(`?page=${page}&size=10`);
  if (!result || !Array.isArray(result.items) || !result.items.every(validDocument) ||
      result.page !== page || result.size !== 10 ||
      !Number.isSafeInteger(result.totalElements) || result.totalElements < 0 ||
      !Number.isInteger(result.totalPages) || result.totalPages !== Math.ceil(result.totalElements / result.size)) {
    throw new Error("服务返回的文档列表格式不正确，请刷新重试。");
  }
  if (page > 0 && page >= result.totalPages) {
    return loadDocumentPage(Math.max(0, result.totalPages - 1));
  }
  documentsPage = result.page;
  documentsTotalPages = result.totalPages;
  renderDocuments(result.items);
  documentsPageLabel.textContent = `第 ${result.totalPages === 0 ? 0 : result.page + 1} / ${result.totalPages} 页 · 共 ${result.totalElements} 篇`;
  documentsStatus.textContent = result.totalElements === 0 ? "知识库暂无文档，请先添加参考资料。" : "列表已更新。";
}

function refreshDocuments(page = documentsPage) {
  return manageDocuments("正在加载文档列表…", () => loadDocumentPage(page));
}

function showDocument(documentId) {
  return manageDocuments("正在加载文档详情…", async () => {
    documentDetail.hidden = true;
    const result = await documentRequest(`/${encodeURIComponent(documentId)}`);
    if (!validDocument(result?.document) || result.document.documentId !== documentId ||
        !Array.isArray(result.chunks) || !result.chunks.every((chunk) =>
          chunk && typeof chunk.id === "string" && typeof chunk.text === "string" &&
          Number.isInteger(chunk.chunkIndex) && chunk.chunkIndex >= 0)) {
      throw new Error("服务返回的文档详情格式不正确，请刷新重试。");
    }
    documentDetailTitle.textContent = result.document.source;
    documentDetailMeta.textContent = `${documentStatuses[result.document.status]} · ${documentMetadata(result.document)}`;
    documentChunks.replaceChildren();
    result.chunks.forEach((chunk) => {
      const block = document.createElement("details");
      block.className = "document-chunk";
      const summary = document.createElement("summary");
      summary.textContent = `分块 ${chunk.chunkIndex + 1} · ${chunk.id}`;
      const text = document.createElement("p");
      text.className = "source-text";
      text.textContent = chunk.text;
      block.append(summary, text);
      documentChunks.append(block);
    });
    if (!result.chunks.length) documentChunks.textContent = "当前没有已入库的文本块。";
    documentDetail.hidden = false;
    documentsStatus.textContent = "详情已加载。点击分块标题查看原文。";
    documentDetail.scrollIntoView({ behavior: "smooth", block: "nearest" });
  });
}

function deleteDocument(item) {
  if (sending || uploading || managing) return;
  if (!window.confirm(`确定删除「${item.source}」？\n文档 ID：${item.documentId}\n将永久移除该文档及其全部向量，所有使用者的后续 RAG 检索都将不再使用这份资料。`)) return;
  return manageDocuments("正在删除文档…", async () => {
    await documentRequest(`/${encodeURIComponent(item.documentId)}`, "DELETE");
    // Keep deletion success separate from a possibly failing list refresh.
    try {
      await loadDocumentPage(documentsPage);
      documentsStatus.textContent = `「${item.source}」已删除。`;
    } catch (error) {
      throw new Error(`「${item.source}」已删除，但列表刷新失败。${error instanceof Error ? error.message : "请手动刷新。"}`);
    }
  });
}

documentsRefresh.addEventListener("click", () => refreshDocuments());
documentsPrevious.addEventListener("click", () => refreshDocuments(Math.max(0, documentsPage - 1)));
documentsNext.addEventListener("click", () => refreshDocuments(documentsPage + 1));
documentDetailClose.addEventListener("click", () => { documentDetail.hidden = true; });
updateControls();
