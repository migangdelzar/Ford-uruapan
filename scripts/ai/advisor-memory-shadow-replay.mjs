import fs from 'node:fs/promises';

function normalize(value) {
  return String(value ?? '').normalize('NFD').replace(/\p{M}/gu, '')
    .toLocaleLowerCase('es-MX').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

function overlap(left, right) {
  const a = new Set(normalize(left).split(/\s+/).filter((word) => word.length > 2));
  const b = new Set(normalize(right).split(/\s+/).filter((word) => word.length > 2));
  if (a.size < 4 || b.size < 4) return 0;
  let common = 0;
  for (const token of a) if (b.has(token)) common += 1;
  return common / Math.max(a.size, b.size);
}

function validate(snapshot) {
  if (!snapshot || typeof snapshot.scenarioId !== 'string' || !snapshot.scenarioId
      || typeof snapshot.systemContext !== 'string'
      || !Array.isArray(snapshot.recentTurns) || !Array.isArray(snapshot.replayTurns)
      || snapshot.replayTurns.length === 0) throw new Error('snapshot must contain systemContext, recentTurns, replayTurns');
  for (const turn of [...snapshot.recentTurns, ...snapshot.replayTurns]) {
    if (!turn || !['user', 'assistant'].includes(turn.role) || typeof turn.content !== 'string' || !turn.content.trim()) {
      throw new Error('snapshot turns must have user/assistant role and non-empty content');
    }
  }
  if (snapshot.replayTurns.some((turn) => turn.role !== 'user')) throw new Error('replayTurns must be user turns');
  for (const turn of snapshot.replayTurns) {
    for (const field of ['expectedTopicTokens', 'unsupportedClaimPatterns']) {
      if (turn[field] !== undefined && (!Array.isArray(turn[field])
          || turn[field].some((value) => typeof value !== 'string' || !value.trim()))) {
        throw new Error(`${field} must contain non-empty strings`);
      }
    }
  }
}

export async function runReplay({ snapshotPath, baseUrl, model, apiKey, fetchImpl = globalThis.fetch }) {
  if (!snapshotPath || !baseUrl || !model || !apiKey || typeof fetchImpl !== 'function') {
    throw new Error('snapshotPath, baseUrl, model, apiKey, and fetchImpl are required');
  }
  const snapshot = JSON.parse(await fs.readFile(snapshotPath, 'utf8'));
  validate(snapshot);
  const messages = [{ role: 'system', content: snapshot.systemContext }, ...snapshot.recentTurns];
  const latencies = [];
  const replies = [];
  let exactRepeatCount = 0;
  let nearRepeatCount = 0;
  let answeredCurrentRequestCount = 0;
  let topicProgressionCount = 0;
  let unsupportedClaimCount = 0;
  let estimatedCostUsd = 0;

  for (const turn of snapshot.replayTurns) {
    messages.push(turn);
    const started = performance.now();
    const response = await fetchImpl(`${baseUrl.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { authorization: `Bearer ${apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify({ model, messages, temperature: 0.2, max_tokens: 500 }),
    });
    latencies.push(Math.round(performance.now() - started));
    if (!response.ok) throw new Error(`model request failed with HTTP ${response.status}`);
    const payload = await response.json();
    const answer = payload?.choices?.[0]?.message?.content;
    if (typeof answer !== 'string' || !answer.trim()) throw new Error('model response has no answer');
    const normalized = normalize(answer);
    if (replies.some((prior) => normalize(prior) === normalized)) exactRepeatCount += 1;
    else if (replies.some((prior) => overlap(prior, answer) >= 0.8)) nearRepeatCount += 1;
    if (normalize(answer).split(' ').some((token) => normalize(turn.content).split(' ').includes(token))) {
      answeredCurrentRequestCount += 1;
    }
    const normalizedAnswer = normalize(answer);
    if (turn.expectedTopicTokens?.some((token) => normalizedAnswer.includes(normalize(token)))) {
      topicProgressionCount += 1;
    }
    for (const pattern of turn.unsupportedClaimPatterns ?? []) {
      if (new RegExp(pattern, 'iu').test(answer)) unsupportedClaimCount += 1;
    }
    replies.push(answer);
    messages.push({ role: 'assistant', content: answer });
    const usage = payload.usage ?? {};
    estimatedCostUsd += (Number(usage.prompt_tokens) || 0) * (Number(process.env.ADVISOR_REPLAY_INPUT_USD_PER_MILLION) || 0) / 1_000_000;
    estimatedCostUsd += (Number(usage.completion_tokens) || 0) * (Number(process.env.ADVISOR_REPLAY_OUTPUT_USD_PER_MILLION) || 0) / 1_000_000;
  }
  const sorted = [...latencies].sort((a, b) => a - b);
  const percentile = (p) => sorted[Math.max(0, Math.ceil(p * sorted.length) - 1)];
  return Object.freeze({ model, turnCount: replies.length, exactRepeatCount, nearRepeatCount,
    answeredCurrentRequestCount, topicProgressionCount, unsupportedClaimCount,
    p50LatencyMs: percentile(0.5), p95LatencyMs: percentile(0.95),
    estimatedCostUsd: Number(estimatedCostUsd.toFixed(6)) });
}

export async function runComparison({ snapshotPath, baseUrl, models, apiKey, fetchImpl = globalThis.fetch }) {
  if (!Array.isArray(models) || models.length < 2 || models.some((model) => !model)) {
    throw new Error('at least two candidate models are required');
  }
  const reports = [];
  for (const model of models) {
    reports.push(await runReplay({ snapshotPath, baseUrl, model, apiKey, fetchImpl }));
  }
  return Object.freeze({ reports: Object.freeze(reports) });
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const [snapshotPath] = process.argv.slice(2);
  const models = (process.env.ADVISOR_REPLAY_MODELS || process.env.ADVISOR_REPLAY_MODEL || '')
    .split(',').map((model) => model.trim()).filter(Boolean);
  const options = { snapshotPath, baseUrl: process.env.ADVISOR_REPLAY_BASE_URL,
    apiKey: process.env.ADVISOR_REPLAY_API_KEY };
  const run = models.length > 1 ? runComparison({ ...options, models })
    : runReplay({ ...options, model: models[0] });
  run
    .then((report) => process.stdout.write(`${JSON.stringify(report)}\n`))
    .catch((error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
