import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { performance } from "node:perf_hooks";

const options = new Map();
for (let i = 2; i < process.argv.length; i += 2) {
  const name = process.argv[i];
  const value = process.argv[i + 1];
  if (!["--base-url", "--output", "--verify-report"].includes(name) || !value || options.has(name)) {
    throw new Error("Usage: node tooling/retrieval/evaluate.mjs [--base-url http://localhost:8080] [--output path] [--verify-report path]");
  }
  options.set(name, value);
}
const base = new URL(options.get("--base-url") || "http://localhost:8080");
if (!["localhost", "127.0.0.1", "[::1]"].includes(base.hostname) ||
    !["http:", "https:"].includes(base.protocol) || base.username || base.password ||
    base.pathname !== "/" || base.search || base.hash) {
  throw new Error("Evaluation only supports an explicit local loopback origin.");
}
const datasetPath = fileURLToPath(new URL("evaluation-cases.json", import.meta.url));
const datasetBytes = await readFile(datasetPath);
const dataset = JSON.parse(datasetBytes);
const hash = (value) => createHash("sha256").update(value).digest("hex");
const datasetSha256 = hash(datasetBytes);
const categories = ["direct", "paraphrase", "multi_source", "near_missing", "unrelated"];
if (!Number.isInteger(dataset.topK) || dataset.topK < 1 || dataset.topK > 20 ||
    !Array.isArray(dataset.developmentSplits) || !dataset.developmentSplits.length ||
    dataset.developmentSplits.includes(dataset.holdoutSplit) ||
    !Number.isFinite(dataset.calibrationSafetyMargin) || dataset.calibrationSafetyMargin < 0 ||
    !Array.isArray(dataset.cases) || !dataset.cases.length ||
    new Set(dataset.cases.map((item) => item.id)).size !== dataset.cases.length ||
    dataset.cases.some((item) => typeof item.id !== "string" ||
      ![...dataset.developmentSplits, dataset.holdoutSplit].includes(item.split) || !categories.includes(item.category) ||
      typeof item.query !== "string" || !item.query.trim() || item.query.length > 2000 ||
      !Array.isArray(item.expectedSources) || item.expectedSources.some((source) => typeof source !== "string" || !source) ||
      new Set(item.expectedSources).size !== item.expectedSources.length ||
      ((["near_missing", "unrelated"].includes(item.category)) !== (item.expectedSources.length === 0)))) {
  throw new Error("Invalid evaluation dataset.");
}

async function request(path, body) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(120000),
  });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}; evaluation stopped without retry.`);
  return response.json();
}

async function snapshotCorpus() {
  const items = [];
  for (let page = 0; ; page++) {
    const response = await request(`/api/knowledge/documents?page=${page}&size=100`);
    if (!Array.isArray(response.items) || !Number.isInteger(response.totalPages)) throw new Error("Invalid document catalog.");
    items.push(...response.items);
    if (page + 1 >= response.totalPages) break;
  }
  if (items.some((item) => item.status === "PROCESSING")) throw new Error("Wait for active document ingestion before evaluating.");
  for (const source of new Set(dataset.cases.flatMap((item) => item.expectedSources))) {
    const matching = items.filter((item) => item.source === source);
    if (matching.length !== 1 || !["READY", "LEGACY"].includes(matching[0].status)) {
      throw new Error(`Expected exactly one usable document titled: ${source}`);
    }
  }
  const manifest = [];
  let chunkCount = 0;
  for (const item of items) {
    const detail = await request(`/api/knowledge/documents/${encodeURIComponent(item.documentId)}`);
    if (detail.document.documentId !== item.documentId || detail.document.status !== item.status ||
        !Array.isArray(detail.chunks) || detail.chunks.length !== item.chunksIndexed) {
      throw new Error("Corpus changed during snapshot; stop and rerun when stable.");
    }
    chunkCount += detail.chunks.length;
    manifest.push({
      id: item.documentId, source: item.source, status: item.status,
      chunks: detail.chunks.map((chunk) => ({ id: chunk.id, index: chunk.chunkIndex, sha256: hash(chunk.text) }))
        .sort((a, b) => a.id.localeCompare(b.id)),
    });
  }
  manifest.sort((a, b) => a.id.localeCompare(b.id));
  return { documentCount: items.length, chunkCount, sha256: hash(JSON.stringify(manifest)) };
}

function summarize(samples, threshold, split) {
  const cases = samples.filter((sample) => split === "calibration"
    ? dataset.developmentSplits.includes(sample.split) : sample.split === dataset.holdoutSplit);
  let positiveCount = 0, hitCount = 0, fullEvidenceCount = 0, falseRejectCount = 0;
  let expectedSourceCount = 0, foundSourceCount = 0, negativeCount = 0, falseAcceptCount = 0;
  let unrelatedCount = 0, unrelatedAccepted = 0, nearMissingCount = 0, nearMissingAccepted = 0;
  let acceptedChunks = 0;
  for (const sample of cases) {
    const accepted = sample.candidates.filter((candidate) => candidate.usable && candidate.score >= threshold);
    acceptedChunks += accepted.length;
    if (sample.expectedSources.length) {
      positiveCount++;
      const found = sample.expectedSources.filter((source) => accepted.some((candidate) => candidate.source === source)).length;
      expectedSourceCount += sample.expectedSources.length;
      foundSourceCount += found;
      if (found > 0) hitCount++;
      if (found === sample.expectedSources.length) fullEvidenceCount++;
      if (!accepted.length) falseRejectCount++;
    } else {
      negativeCount++;
      if (accepted.length) falseAcceptCount++;
      if (sample.category === "unrelated") {
        unrelatedCount++;
        if (accepted.length) unrelatedAccepted++;
      } else {
        nearMissingCount++;
        if (accepted.length) nearMissingAccepted++;
      }
    }
  }
  if (!positiveCount || !negativeCount) throw new Error("Each split needs answerable and unanswerable cases.");
  return {
    caseCount: cases.length, positiveCount, negativeCount, hitCount, fullEvidenceCount,
    expectedSourceCount, foundSourceCount, falseRejectCount, falseAcceptCount,
    hitRate: hitCount / positiveCount,
    sourceRecall: foundSourceCount / expectedSourceCount,
    fullEvidenceRate: fullEvidenceCount / positiveCount,
    falseRejectRate: falseRejectCount / positiveCount,
    falseAcceptRate: falseAcceptCount / negativeCount,
    unrelatedCount, unrelatedAccepted, nearMissingCount, nearMissingAccepted, acceptedChunks,
  };
}

function selectThreshold(samples) {
  const baseline = summarize(samples, 0, "calibration");
  const evidenceScores = samples.filter((sample) => dataset.developmentSplits.includes(sample.split))
    .flatMap((sample) => sample.expectedSources.flatMap((source) => {
      const matches = sample.candidates.filter((candidate) => candidate.usable && candidate.source === source);
      return matches.length ? [Math.max(...matches.map((candidate) => candidate.score))] : [];
    }));
  if (!evidenceScores.length) throw new Error("No annotated evidence found in the development baseline.");
  const ceiling = Math.max(0, Math.min(...evidenceScores) - dataset.calibrationSafetyMargin);
  const grid = [0, ...Array.from({ length: 61 }, (_, i) => (i + 20) / 100)].map((threshold) => ({
    threshold, metrics: summarize(samples, threshold, "calibration"),
  }));
  const eligible = grid.filter(({ threshold, metrics }) =>
    threshold <= ceiling && metrics.foundSourceCount >= baseline.foundSourceCount &&
    metrics.hitCount >= baseline.hitCount &&
    metrics.fullEvidenceCount >= baseline.fullEvidenceCount &&
    metrics.falseRejectCount <= baseline.falseRejectCount);
  eligible.sort((a, b) => a.metrics.falseAcceptCount - b.metrics.falseAcceptCount || a.threshold - b.threshold);
  return { selected: eligible[0].threshold, ceiling, grid };
}

function compactMatches(matches) {
  if (!Array.isArray(matches) || matches.length > dataset.topK) throw new Error("Invalid search response.");
  return matches.map((match) => {
    if (typeof match.id !== "string" || !Number.isFinite(match.score) ||
        match.score < -1 || match.score > 1 || typeof match.metadata?.source !== "string" ||
        (match.text !== null && typeof match.text !== "string")) throw new Error("Invalid candidate metadata or score.");
    return { chunkId: match.id, source: match.metadata.source, score: match.score, usable: Boolean(match.text?.trim()) };
  });
}

async function collectSamples() {
  const samples = [];
  for (const item of dataset.cases) {
    const start = performance.now();
    const result = await request("/api/knowledge/search", { query: item.query, topK: dataset.topK, similarityThreshold: 0 });
    samples.push({ ...item, elapsedMs: Math.round(performance.now() - start), candidates: compactMatches(result) });
    console.log(`Collected ${samples.length}/${dataset.cases.length}: ${item.id}`);
  }
  return samples;
}

async function verifyServer(report) {
  if (report.datasetSha256 !== datasetSha256) throw new Error("Dataset differs from frozen report.");
  const threshold = report.selectedThreshold;
  if (!Number.isFinite(threshold) || threshold < 0 || threshold > 1) throw new Error("Invalid frozen threshold.");
  const observations = [];
  const canonical = (candidates) => candidates.map((candidate) => candidate.chunkId).sort().join(",");
  for (const item of dataset.cases) {
    const frozen = report.samples.find((sample) => sample.id === item.id);
    if (!frozen) throw new Error(`Missing frozen sample: ${item.id}`);
    const start = performance.now();
    const raw = await request("/api/knowledge/search/diagnostics", {
      query: item.query, topK: dataset.topK, similarityThreshold: 0,
    });
    const currentCandidates = compactMatches(raw.matches);
    if (canonical(currentCandidates) !== canonical(frozen.candidates) ||
        currentCandidates.some((candidate) => {
          const saved = frozen.candidates.find((entry) => entry.chunkId === candidate.chunkId);
          return !saved || Math.abs(candidate.score - saved.score) > 0.00001;
        })) {
      throw new Error(`Retrieval baseline drifted for ${item.id}; do not silently reuse the calibration.`);
    }
    // Omit the override: this also checks the deployed configuration, not just request filtering.
    const actual = await request("/api/knowledge/search/diagnostics", { query: item.query, topK: dataset.topK });
    const expected = frozen.candidates.filter((candidate) => candidate.usable && candidate.score >= threshold);
    const matches = compactMatches(actual.matches);
    const diagnostic = actual.diagnostics;
    if (!diagnostic || diagnostic.similarityThreshold !== threshold || diagnostic.topK !== dataset.topK ||
        diagnostic.candidateCount !== frozen.candidates.length || diagnostic.acceptedCount !== expected.length ||
        diagnostic.rejectedCount !== frozen.candidates.length - expected.length ||
        !Number.isFinite(diagnostic.elapsedMs) || diagnostic.elapsedMs < 0 ||
        !Array.isArray(diagnostic.candidates) || diagnostic.candidates.length !== frozen.candidates.length ||
        canonical(matches) !== canonical(expected)) {
      throw new Error(`Server filtering or configuration mismatch: ${item.id}`);
    }
    for (const candidate of diagnostic.candidates) {
      const saved = frozen.candidates.find((entry) => entry.chunkId === candidate.chunkId);
      const reason = saved && (!saved.usable ? "EMPTY_TEXT" : saved.score >= threshold ? "ACCEPTED" : "BELOW_THRESHOLD");
      if (!saved || Math.abs(candidate.score - saved.score) > 0.00001 ||
          candidate.accepted !== (reason === "ACCEPTED") || candidate.reason !== reason) {
        throw new Error(`Server diagnostics mismatch: ${item.id}`);
      }
    }
    const legacy = compactMatches(await request("/api/knowledge/search", { query: item.query, topK: dataset.topK }));
    if (canonical(legacy) !== canonical(expected)) throw new Error(`Legacy search disagrees: ${item.id}`);
    observations.push({ id: item.id, elapsedMs: diagnostic.elapsedMs, totalVerificationMs: Math.round(performance.now() - start) });
    console.log(`Verified ${observations.length}/${dataset.cases.length}: ${item.id}`);
  }
  return observations;
}

console.log("Local-only retrieval evaluation; no document writes and no DeepSeek calls.");
const corpus = await snapshotCorpus();
if (options.has("--verify-report")) {
  const report = JSON.parse(await readFile(options.get("--verify-report"), "utf8"));
  if (corpus.sha256 !== report.corpus.sha256) throw new Error("Corpus differs from frozen evaluation; regenerate baseline explicitly.");
  const observations = await verifyServer(report);
  if ((await snapshotCorpus()).sha256 !== corpus.sha256) throw new Error("Corpus changed during verification.");
  report.serverVerification = { verifiedAt: new Date().toISOString(), baseUrl: base.origin, cases: observations.length, observations };
  await writeFile(options.get("--output") || options.get("--verify-report"), JSON.stringify(report, null, 2) + "\n");
  console.log(`Verified both search APIs and default threshold ${report.selectedThreshold} on ${observations.length} fixed cases.`);
} else {
  const samples = await collectSamples();
  if ((await snapshotCorpus()).sha256 !== corpus.sha256) throw new Error("Corpus changed during evaluation.");
  const selection = selectThreshold(samples);
  const metrics = Object.fromEntries(["calibration", "validation"].map((split) => [split, {
    baseline: summarize(samples, 0, split), selected: summarize(samples, selection.selected, split),
  }]));
  const before = metrics.validation.baseline;
  const after = metrics.validation.selected;
  const demonstratedImprovement = after.falseAcceptCount < before.falseAcceptCount &&
    after.foundSourceCount >= before.foundSourceCount && after.hitCount >= before.hitCount &&
    after.fullEvidenceCount >= before.fullEvidenceCount && after.falseRejectCount <= before.falseRejectCount;
  const report = {
    version: 1, generatedAt: new Date().toISOString(), baseUrl: base.origin, model: "bge-m3",
    datasetSha256, corpus, topK: dataset.topK, selectedThreshold: selection.selected,
    selectionPolicy: "Development splits only: preserve baseline evidence/coverage and leave a configured margin below the weakest retrieved annotated source. Minimize false accepts; ties choose the lowest threshold. The confirmation split never tunes the threshold.",
    developmentSplits: dataset.developmentSplits, holdoutSplit: dataset.holdoutSplit,
    calibrationSafetyMargin: dataset.calibrationSafetyMargin, thresholdCeiling: selection.ceiling,
    history: dataset.history,
    demonstratedImprovement,
    limitations: "Small manually authored dataset; source relevance, not generated-answer correctness. High semantic similarity cannot establish answerability. Network timings are single-run observations, not performance benchmarks. No model generation was invoked.",
    metrics, calibrationGrid: selection.grid, samples,
  };
  const output = options.get("--output") || fileURLToPath(new URL("evaluation-report.json", import.meta.url));
  await writeFile(output, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify({ output, selectedThreshold: selection.selected, demonstratedImprovement, metrics }, null, 2));
}
