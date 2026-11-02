import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { runComparison, runReplay } from './advisor-memory-shadow-replay.mjs';

async function withSnapshot(value, run) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'advisor-replay-'));
  const snapshotPath = path.join(directory, 'snapshot.json');
  await fs.writeFile(snapshotPath, JSON.stringify(value));
  try { await run(snapshotPath); } finally { await fs.rm(directory, { recursive: true, force: true }); }
}

test('replays synthetic turns and returns aggregate-only report without creating delivery clients', async () => {
  const snapshot = { scenarioId: 'synthetic', systemContext: 'Contexto sintético.',
    recentTurns: [{ role: 'user', content: 'Hola' }, { role: 'assistant', content: 'Bienvenido.' }],
    replayTurns: [{ role: 'user', content: '¿Qué precio tiene Bronco?' }, { role: 'user', content: '¿Qué versiones tiene Bronco?' }] };
  let calls = 0;
  await withSnapshot(snapshot, async (snapshotPath) => {
    const report = await runReplay({ snapshotPath, baseUrl: 'https://example.invalid/v1',
      model: 'test-model', apiKey: 'test-secret', fetchImpl: async (_url, request) => {
        calls += 1;
        assert.match(request.headers.authorization, /^Bearer /);
        const body = JSON.parse(request.body);
        assert.equal(body.model, 'test-model');
        return { ok: true, json: async () => ({ choices: [{ message: { content: calls === 1
          ? 'La Bronco tiene un precio publicado y varias versiones disponibles en agencia Ford.'
          : 'La Bronco tiene un precio publicado y varias versiones disponibles actualmente en agencia Ford.' } }], usage: { prompt_tokens: 100, completion_tokens: 20 } }) };
      } });
    assert.equal(calls, 2);
    assert.equal(report.turnCount, 2);
    assert.equal(report.nearRepeatCount, 1);
    assert.equal(JSON.stringify(report).includes('Bronco'), false);
    assert.equal(JSON.stringify(report).includes('synthetic'), false);
    assert.equal(Object.keys(report).some((key) => /text|prompt|conversation|phone|account/i.test(key)), false);
  });
});

test('rejects malformed or empty replay turns before making a request', async () => {
  await withSnapshot({ scenarioId: 'synthetic-invalid', systemContext: 'safe', recentTurns: [], replayTurns: [{ role: 'assistant', content: 'bad' }] },
    async (snapshotPath) => {
      let called = false;
      await assert.rejects(runReplay({ snapshotPath, baseUrl: 'https://example.invalid/v1', model: 'x', apiKey: 'y',
        fetchImpl: async () => { called = true; } }), /replayTurns must be user turns/);
      assert.equal(called, false);
    });
});

test('rejects missing credentials before reading private snapshot', async () => {
  await assert.rejects(runReplay({ snapshotPath: '/missing/private-snapshot.json', baseUrl: 'x', model: 'm' }),
    /snapshotPath, baseUrl, model, apiKey, and fetchImpl are required/);
});

test('compares candidate models sequentially against the same snapshot and returns aggregates only', async () => {
  const snapshot = { scenarioId: 'synthetic-comparison', systemContext: 'same context', recentTurns: [],
    replayTurns: [{ role: 'user', content: 'Synthetic question one' }] };
  const observed = [];
  await withSnapshot(snapshot, async (snapshotPath) => {
    const comparison = await runComparison({ snapshotPath, baseUrl: 'https://example.invalid/v1',
      models: ['deepseek-candidate', 'fallback-candidate'], apiKey: 'test-secret',
      fetchImpl: async (_url, request) => {
        const body = JSON.parse(request.body);
        observed.push({ model: body.model, messages: body.messages });
        return { ok: true, json: async () => ({ choices: [{ message: { content: 'Synthetic answer.' } }] }) };
      } });
    assert.deepEqual(comparison.reports.map((report) => report.model), ['deepseek-candidate', 'fallback-candidate']);
    assert.deepEqual(observed[0].messages, observed[1].messages);
    assert.equal(JSON.stringify(comparison).includes('Synthetic answer'), false);
  });
});

test('scores annotated topic progression and synthetic unsupported-claim patterns as aggregates', async () => {
  const snapshot = { scenarioId: 'synthetic-metric-case', systemContext: 'safe synthetic context', recentTurns: [],
    replayTurns: [
      { role: 'user', content: 'Synthetic topic price', expectedTopicTokens: ['precio'], unsupportedClaimPatterns: ['garantizado'] },
      { role: 'user', content: 'Synthetic topic trim', expectedTopicTokens: ['versiones'], unsupportedClaimPatterns: ['entrega mañana'] },
    ] };
  await withSnapshot(snapshot, async (snapshotPath) => {
    let call = 0;
    const report = await runReplay({ snapshotPath, baseUrl: 'https://example.invalid/v1', model: 'test-model', apiKey: 'x',
      fetchImpl: async () => ({ ok: true, json: async () => ({ choices: [{ message: { content: call++ === 0
        ? 'El precio publicado puede variar. Está garantizado para todos.'
        : 'Hay varias versiones. La entrega mañana está confirmada.' } }] }) }) });

    assert.equal(report.topicProgressionCount, 2);
    assert.equal(report.unsupportedClaimCount, 2);
    assert.equal(JSON.stringify(report).includes('garantizado'), false);
    assert.equal(JSON.stringify(report).includes('precio'), false);
  });
});
