"use strict";

const form = document.querySelector("#chat-form");
const input = document.querySelector("#message-input");
const messages = document.querySelector("#messages");
const sendButton = document.querySelector("#send-button");
const status = document.querySelector("#request-status");
let sending = false;

function updateControls() {
  sendButton.disabled = sending || !input.value.trim();
  sendButton.textContent = sending ? "等待回复…" : "发送";
  messages.querySelectorAll(".retry-button").forEach((button) => {
    button.disabled = sending;
  });
}

function scrollToLatest() {
  messages.scrollTop = messages.scrollHeight;
}

function appendMessage(role, text) {
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
  label.textContent = role === "user" ? "我" : "DeepSeek";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  // Treat both user input and model output as text, never executable markup.
  bubble.textContent = text;
  body.append(label, bubble);
  row.append(avatar, body);
  messages.append(row);
  scrollToLatest();
  return { row, bubble };
}

async function send(message, failedRow) {
  if (sending) return;
  sending = true;
  if (failedRow) {
    failedRow.remove();
  } else {
    appendMessage("user", message);
    input.value = "";
  }
  const pending = appendMessage("assistant", "正在思考，请稍候…");
  pending.row.classList.add("pending");
  status.textContent = "正在等待 DeepSeek 回复";
  updateControls();

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 120000);
  try {
    const response = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`请求失败（HTTP ${response.status}），请检查服务配置后重试。`);
    }
    const data = await response.json();
    if (typeof data.reply !== "string" || !data.reply.trim()) {
      throw new Error("服务没有返回有效回复，请重试。");
    }
    pending.bubble.textContent = data.reply;
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
    retry.addEventListener("click", () => send(message, pending.row));
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
  if (message && !sending) {
    void send(message);
  }
});

input.addEventListener("input", updateControls);
input.addEventListener("keydown", (event) => {
  // Do not submit when Enter is used to confirm Chinese IME composition.
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    if (!sending) form.requestSubmit();
  }
});
updateControls();
