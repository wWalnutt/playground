"use strict";

const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const messages = document.querySelector("#messages");
const sendButton = document.querySelector("#send-button");
const status = document.querySelector("#request-status");
const modeSelect = document.querySelector("#chat-mode");
const modeHint = document.querySelector("#mode-hint");
const modeNames = { chat: "普通聊天", rag: "知识库 RAG" };
let sending = false;

function updateControls() {
  sendButton.disabled = sending || !input.value.trim();
  sendButton.textContent = sending ? "等待回复…" : "发送";
  modeSelect.disabled = sending;
  messages.querySelectorAll(".retry-button").forEach((button) => {
    button.disabled = sending;
  });
}

function scrollToLatest() {
  messages.scrollTop = messages.scrollHeight;
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
  messages.append(row);
  scrollToLatest();
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
  if (sending) return;
  sending = true;
  if (failedRow) {
    failedRow.remove();
  } else {
    appendMessage("user", message, mode);
    input.value = "";
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
    scrollToLatest();
  }
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  const message = input.value.trim();
  if (modeSelect.value === "rag" && message.length > 2000) {
    input.setCustomValidity("RAG 问题不能超过 2000 个字符。");
    input.reportValidity();
    return;
  }
  if (message && !sending) {
    void send(message, modeSelect.value);
  }
});

input.addEventListener("input", () => {
  input.setCustomValidity("");
  updateControls();
});
modeSelect.addEventListener("change", () => {
  input.setCustomValidity("");
  modeHint.textContent = modeSelect.value === "rag"
    ? "知识库 RAG：检索 3 条资料后回答（问题最多 2000 字符）。需启用 vectors 配置；相关原文会发送给 DeepSeek。"
    : "普通聊天：直接调用 DeepSeek，不检索知识库。";
  updateControls();
});
input.addEventListener("keydown", (event) => {
  // Do not submit when Enter is used to confirm Chinese IME composition.
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    if (!sending) form.requestSubmit();
  }
});
updateControls();
