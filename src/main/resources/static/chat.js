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
const drafts = { chat: "", rag: "" };
let activeView = "chat";
let sending = false;
let uploading = "";

function updateControls() {
  const busy = sending || uploading !== "";
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
}

function scrollToLatest(mode = activeView) {
  const messages = messageLists[mode];
  if (messages) messages.scrollTop = messages.scrollHeight;
}

function switchView(view) {
  if (sending || uploading || view === activeView) return;
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
    upload: "输入文本或上传文件，构建你的知识库",
  }[view];
  modeHint.textContent = {
    chat: "普通聊天：直接调用 DeepSeek，不检索知识库。",
    rag: "RAG 对话：检索 3 条资料后回答（问题最多 2000 字符）。需启用 vectors 配置；相关原文会发送给 DeepSeek。",
    upload: "两种方式都将资料分块、向量化并存入知识库。需要启用 vectors 配置，并运行 PostgreSQL 和 Ollama。",
  }[view];
  input.value = view === "upload" ? "" : drafts[view];
  input.setCustomValidity("");
  status.textContent = "";
  updateControls();
  scrollToLatest();
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
    note.textContent = "未检索到可用资料，本次未调用 DeepSeek。";
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

async function send(message, mode, failedRow) {
  if (sending || uploading) return;
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
    pending.bubble.textContent = reply;
    if (mode === "rag") renderSources(pending.body, data.sources);
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
  if (message && !sending && !uploading) {
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
    if (!sending && !uploading) form.requestSubmit();
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
  if (sending || uploading) return;
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
  if (sending || uploading) return;
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
  if (sending || uploading) return;
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
        `入库失败（HTTP ${response.status}），请检查 PostgreSQL、Ollama 和后端日志。可能已有部分数据入库，重新上传前请确认。`);
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
  }
}
updateControls();
