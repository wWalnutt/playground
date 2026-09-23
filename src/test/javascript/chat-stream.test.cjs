const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const { test } = require("node:test");

class Element {
  constructor(tag = "div") {
    this.tagName = tag;
    this.children = [];
    this.dataset = {};
    this.attributes = {};
    this.listeners = {};
    this.value = "";
    this.files = [];
    this.className = "";
    this.hidden = false;
    this.disabled = false;
    this.ownText = "";
    this.classList = {
      add: (name) => { if (!this.classList.contains(name)) this.className += ` ${name}`; },
      remove: (name) => { this.className = this.className.split(" ").filter((value) => value !== name).join(" "); },
      contains: (name) => this.className.split(" ").includes(name),
    };
  }
  get textContent() { return this.ownText + this.children.map((child) => child.textContent).join(""); }
  set textContent(value) { this.ownText = value; this.children = []; }
  append(...children) {
    children.forEach((child) => { child.parent = this; this.children.push(child); });
  }
  appendData(text) { this.ownText += text; }
  replaceChildren(...children) { this.ownText = ""; this.children = []; this.append(...children); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter((child) => child !== this); }
  setAttribute(name, value) { this.attributes[name] = value; }
  removeAttribute(name) { delete this.attributes[name]; }
  addEventListener(name, action) { this.listeners[name] = action; }
  setCustomValidity() {}
  reportValidity() {}
  scrollIntoView() {}
  querySelectorAll(selector) {
    return this.children.flatMap((child) => [
      ...(selector === "button" && child.tagName === "button" ||
        selector.startsWith(".") && child.classList.contains(selector.slice(1)) ? [child] : []),
      ...child.querySelectorAll(selector),
    ]);
  }
}

const frame = (event, data) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
const tick = () => new Promise(setImmediate);
const encoder = new TextEncoder();

function controlledResponse(signal) {
  let controller;
  let cancelled = false;
  const onAbort = () => controller.error(new DOMException("Aborted", "AbortError"));
  const body = new ReadableStream({
    start(value) { controller = value; signal?.addEventListener("abort", onAbort, { once: true }); },
    cancel() { cancelled = true; signal?.removeEventListener("abort", onAbort); },
  });
  return {
    response: new Response(body, { headers: { "Content-Type": "text/event-stream;charset=UTF-8" } }),
    emit: (text) => controller.enqueue(encoder.encode(text)),
    close: () => { signal?.removeEventListener("abort", onAbort); controller.close(); },
    get cancelled() { return cancelled; },
  };
}

function environment() {
  const html = fs.readFileSync("src/main/resources/static/index.html", "utf8");
  const nodes = Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], new Element()]));
  const navigation = ["chat", "rag", "upload"].map((view) => {
    const button = new Element("button");
    button.dataset.view = view;
    return button;
  });
  const responses = [], requests = [];
  let timeout;
  const context = vm.createContext({
    document: {
      querySelector: (selector) => {
        assert.ok(nodes[selector.slice(1)], `Missing selector ${selector}`);
        return nodes[selector.slice(1)];
      },
      querySelectorAll: () => navigation,
      createElement: (tag) => new Element(tag),
      createTextNode: (text) => { const node = new Element("#text"); node.textContent = text; return node; },
    },
    window: { confirm: () => true },
    fetch: async (url, options) => {
      requests.push({ url, ...options });
      assert.ok(responses.length, `Unexpected fetch ${url}`);
      const response = responses.shift();
      return typeof response === "function" ? response(options) : response;
    },
    setTimeout: (handler, milliseconds) => {
      if (milliseconds === 125000) { timeout = handler; return 1; }
      throw new Error(`Unexpected timer ${milliseconds}`);
    },
    clearTimeout: () => { timeout = undefined; },
    AbortController, TextDecoder, FormData,
    console: { warn() {} },
  });
  vm.runInContext(fs.readFileSync("src/main/resources/static/chat.js", "utf8"), context);
  return {
    nodes, responses, requests, context,
    run: (code) => vm.runInContext(code, context),
    expire: () => timeout(),
    answer: (mode = "chat") => nodes[mode === "rag" ? "rag-messages" : "messages"].children.at(-1),
  };
}

function finiteResponse(text, byteSize = 7) {
  const bytes = encoder.encode(text);
  return new Response(new ReadableStream({
    start(controller) {
      for (let i = 0; i < bytes.length; i += byteSize) controller.enqueue(bytes.slice(i, i + byteSize));
      controller.close();
    },
  }), { headers: { "Content-Type": "text/event-stream" } });
}

const sources = [{ reference: 1, chunkId: "chunk1", text: "<script>reference</script>", metadata: { source: "Kotlin" }, score: 0.8 }];
const diagnostics = {
  topK: 3, similarityThreshold: 0.46, candidateCount: 1, acceptedCount: 1, rejectedCount: 0, elapsedMs: 20,
  candidates: [{ chunkId: "chunk1", source: "Kotlin", score: 0.8, accepted: true, reason: "ACCEPTED" }],
};

test("SSE parser handles UTF-8 byte boundaries, comments, CRLF, CR, multiple frames and multiline data", async () => {
  const env = environment();
  const events = [];
  env.context.response = finiteResponse(
    ': heartbeat\r\n\r\nevent: delta\r\ndata: {"text":\r\ndata: "中文🙂\\n第二行"}\r\n\r\n' +
    'event: delta\rdata: {"text":"尾部"}\r\r' + frame("done", { modelCalled: true }), 1,
  );
  env.context.callback = (name, data) => { events.push([name, data]); return name === "done"; };
  await env.run("readEventStream(response, callback)");
  assert.deepEqual(JSON.parse(JSON.stringify(events)), [
    ["delta", { text: "中文🙂\n第二行" }], ["delta", { text: "尾部" }], ["done", { modelCalled: true }],
  ]);
});

test("plain chat displays the first delta while upstream remains open and waits for done", async () => {
  const env = environment();
  let stream;
  env.responses.push(({ signal }) => { stream = controlledResponse(signal); return stream.response; });
  const pending = env.run("send('original question', 'chat')");
  await tick();
  assert.equal(env.requests[0].url, "/api/chat/stream");
  assert.equal(env.nodes["stop-button"].hidden, false);
  stream.emit(frame("status", { stage: "generating" }) + frame("delta", { text: "第一段" }));
  await tick();
  assert.match(env.answer().textContent, /第一段/);
  assert.equal(env.run("sending"), true);
  stream.emit(frame("delta", { text: "\n第二段🙂" }) + frame("done", { modelCalled: true }));
  await pending;
  assert.equal(env.answer().children[1].children[1].textContent, "第一段\n第二段🙂");
  assert.equal(env.nodes["request-status"].textContent, "回答已完成");
  assert.equal(env.nodes["stop-button"].hidden, true);
  assert.equal(stream.cancelled, true);
});

test("RAG renders sources before text and marks invocation only at completion", async () => {
  const env = environment();
  let stream;
  env.responses.push(({ signal }) => { stream = controlledResponse(signal); return stream.response; });
  const pending = env.run("send('rag question', 'rag')");
  await tick();
  stream.emit(frame("status", { stage: "retrieving" }) + frame("sources", { sources, diagnostics }));
  await tick();
  assert.match(env.answer("rag").textContent, /生成尚未完成/);
  assert.match(env.answer("rag").textContent, /<script>reference<\/script>/);
  stream.emit(frame("delta", { text: "答案 [1]" }) + frame("done", { modelCalled: true }));
  await pending;
  assert.match(env.answer("rag").textContent, /已调用 DeepSeek/);
  assert.match(env.answer("rag").textContent, /答案 \[1\]/);
});

test("RAG threshold refusal is a complete local answer without model invocation", async () => {
  const env = environment();
  env.responses.push(finiteResponse(
    frame("sources", { sources: [], diagnostics: { ...diagnostics, candidateCount: 0, acceptedCount: 0, candidates: [] } }) +
    frame("delta", { text: "没有达到阈值的资料。" }) + frame("done", { modelCalled: false }),
  ));
  await env.run("send('unknown', 'rag')");
  assert.equal(env.nodes["request-status"].textContent, "回答已完成");
  assert.match(env.answer("rag").textContent, /未调用 DeepSeek/);
});

test("stop retains partial text, releases controls and retries the original question in a new answer", async () => {
  const env = environment();
  let stream;
  env.responses.push(({ signal }) => { stream = controlledResponse(signal); return stream.response; });
  const pending = env.run("send('retry this question', 'chat')");
  await tick();
  stream.emit(frame("delta", { text: "未完成内容" }));
  await tick();
  const previous = env.answer();
  env.nodes["stop-button"].listeners.click();
  await pending;
  assert.equal(env.requests[0].signal.aborted, true);
  assert.equal(env.nodes["request-status"].textContent, "已停止生成");
  assert.match(previous.textContent, /未完成内容/);
  assert.match(previous.textContent, /不完整回答/);
  assert.equal(env.run("sending"), false);
  env.responses.push(finiteResponse(frame("delta", { text: "完整重试" }) + frame("done", { modelCalled: true })));
  await previous.querySelectorAll(".retry-button")[0].listeners.click();
  assert.equal(JSON.parse(env.requests[1].body).message, "retry this question");
  assert.notEqual(env.answer(), previous);
  assert.match(previous.textContent, /未完成内容/);
  assert.equal(previous.querySelectorAll(".retry-button").length, 0);
  assert.equal(env.nodes["request-status"].textContent, "回答已完成");
});

test("timeout before text leaves an incomplete answer, not a successful empty message", async () => {
  const env = environment();
  env.responses.push(({ signal }) => controlledResponse(signal).response);
  const pending = env.run("send('slow', 'chat')");
  await tick();
  env.expire();
  await pending;
  assert.match(env.answer().textContent, /超时/);
  assert.equal(env.nodes["request-status"].textContent, "回答未完成");
});

test("stop during RAG retrieval does not invent sources or a completed model call", async () => {
  const env = environment();
  let stream;
  env.responses.push(({ signal }) => { stream = controlledResponse(signal); return stream.response; });
  const pending = env.run("send('stop during retrieval', 'rag')");
  await tick();
  stream.emit(frame("status", { stage: "retrieving" }));
  await tick();
  env.nodes["stop-button"].listeners.click();
  await pending;
  assert.equal(env.nodes["request-status"].textContent, "已停止生成");
  assert.doesNotMatch(env.answer("rag").textContent, /已调用 DeepSeek|检索诊断/);
  assert.equal(env.run("sending"), false);
});

test("RAG failure after sources retains evidence and marks generation unfinished", async () => {
  const env = environment();
  env.responses.push(finiteResponse(
    frame("sources", { sources, diagnostics }) + frame("delta", { text: "部分答案" }) +
    frame("error", { code: "INCOMPLETE", message: "提供方未完整结束。" }),
  ));
  await env.run("send('rag failure', 'rag')");
  assert.match(env.answer("rag").textContent, /部分答案/);
  assert.match(env.answer("rag").textContent, /生成尚未完成/);
  assert.match(env.answer("rag").textContent, /检索来源/);
  assert.equal(env.nodes["request-status"].textContent, "回答未完成");
});

test("midstream errors and premature EOF preserve partial text and never report completion", async () => {
  for (const suffix of [
    frame("error", { code: "PROVIDER_ERROR", message: "模型流中断。" }),
    "",
    'event: done\ndata: {"modelCalled":true}',
    'event: delta\ndata: {invalid}\n\n',
  ]) {
    const env = environment();
    env.responses.push(finiteResponse(frame("delta", { text: "已有内容" }) + suffix));
    await env.run("send('failure', 'chat')");
    assert.match(env.answer().textContent, /已有内容/);
    assert.match(env.answer().textContent, /不完整回答/);
    assert.equal(env.nodes["request-status"].textContent, "回答未完成");
    assert.equal(env.run("sending"), false);
  }
});

test("malformed completion, wrong content type and invalid event order are rejected", async () => {
  const cases = [
    [finiteResponse(frame("done", { modelCalled: true })), "chat"],
    [finiteResponse(frame("delta", { text: " " }) + frame("done", { modelCalled: true })), "chat"],
    [finiteResponse(frame("delta", { text: "text" }) + frame("done", { modelCalled: false })), "chat"],
    [finiteResponse(frame("delta", { text: "before sources" })), "rag"],
    [finiteResponse(frame("unknown", {})), "chat"],
    [new Response('{"reply":"buffered"}', { headers: { "Content-Type": "application/json" } }), "chat"],
    [new Response("", { status: 404 }), "rag"],
  ];
  for (const [response, mode] of cases) {
    const env = environment();
    env.responses.push(response);
    await env.run(`send('bad response', '${mode}')`);
    assert.equal(env.nodes["request-status"].textContent, "回答未完成");
  }
});

test("unterminated oversized frames are bounded", async () => {
  const env = environment();
  env.context.response = finiteResponse("data: " + "x".repeat(1048577), 65536);
  env.context.callback = () => false;
  await assert.rejects(env.run("readEventStream(response, callback)"), /事件过大/);
});

test("invalid UTF-8 fails rather than silently corrupting the answer", async () => {
  const env = environment();
  env.context.response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(Uint8Array.of(0xc3, 0x28)); controller.close(); },
  }), { headers: { "Content-Type": "text/event-stream" } });
  env.context.callback = () => assert.fail("No event expected");
  await assert.rejects(env.run("readEventStream(response, callback)"));
});
