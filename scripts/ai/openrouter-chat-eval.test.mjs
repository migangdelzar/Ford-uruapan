import test from 'node:test';
import assert from 'node:assert/strict';
import {
  dispatchFixtureTool,
  evaluateTranscript,
  runConversation,
} from './openrouter-chat-eval.mjs';

test('runs a multi-turn tool call loop and preserves the assistant transcript', async () => {
  const requests = [];
  let call = 0;
  const fetchImpl = async (_url, init) => {
    requests.push(JSON.parse(init.body));
    call += 1;
    if (call === 1) {
      return jsonResponse({
        choices: [{
          message: {
            role: 'assistant',
            content: null,
            tool_calls: [{
              id: 'call-search-1',
              type: 'function',
              function: { name: 'search_vehicles', arguments: '{"text":"Lobo Raptor 2026"}' },
            }],
          },
        }],
      });
    }
    return jsonResponse({
      choices: [{ message: {
        role: 'assistant',
        content: 'Encontré la Lobo Raptor 2026. ¿Quieres conocer su rendimiento?',
      } }],
    });
  };

  const transcript = await runConversation({
    fetchImpl,
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: 'test-key',
    model: 'openrouter/free',
    maxToolRounds: 2,
    scenario: {
      id: 'raptor-search',
      turns: [{ role: 'user', content: 'Estoy interesado en la Lobo Raptor.' }],
    },
  });

  assert.deepEqual(transcript.toolNames, ['search_vehicles']);
  assert.equal(transcript.turns[0].response,
    'Encontré la Lobo Raptor 2026. ¿Quieres conocer su rendimiento?');
  assert.equal(requests[0].model, 'openrouter/free');
  assert.equal(requests[0].tools[0].function.name, 'search_vehicles');
  assert.equal(requests[1].messages.at(-1).role, 'tool');
});

test('returns authorized promotion evidence to the model before its final answer', async () => {
  const requests = [];
  let call = 0;
  const fetchImpl = async (_url, init) => {
    requests.push(JSON.parse(init.body));
    call += 1;
    if (call === 1) {
      return jsonResponse({ choices: [{ message: {
        role: 'assistant', content: null,
        tool_calls: [{ id: 'promotion-1', type: 'function', function: {
          name: 'list_promotions', arguments: '{"model":"Ranger"}',
        } }],
      } }] });
    }
    return jsonResponse({ choices: [{ message: {
      role: 'assistant', content: 'La ficha de ejemplo muestra una oferta para Ranger; confirmaré su vigencia.',
    } }] });
  };

  const transcript = await runConversation({
    fetchImpl, apiKey: 'test-key', model: 'openrouter/free',
    scenario: { id: 'promotions', turns: [{ role: 'user', content: '¿Qué promociones hay para la Ranger?' }] },
  });

  assert.deepEqual(transcript.toolNames, ['list_promotions']);
  assert.equal(requests[1].messages.at(-1).role, 'tool');
  assert.match(requests[1].messages.at(-1).content, /fixture_offers/);
  assert.match(transcript.turns[0].response, /Ranger/);
});

test('stops a model that never produces a final answer at the tool round limit', async () => {
  const fetchImpl = async () => jsonResponse({
    choices: [{ message: {
      role: 'assistant',
      content: null,
      tool_calls: [{
        id: 'call-loop',
        type: 'function',
        function: { name: 'search_vehicles', arguments: '{}' },
      }],
    } }],
  });

  await assert.rejects(() => runConversation({
    fetchImpl,
    baseUrl: 'https://openrouter.ai/api/v1',
    apiKey: 'test-key',
    model: 'test-model',
    maxToolRounds: 1,
    scenario: { id: 'loop', turns: [{ role: 'user', content: 'Ford' }] },
  }), /tool round limit/);
});

test('retries provider throttling with a bounded backoff', async () => {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    if (calls === 1) return jsonResponse({ error: 'rate limited' }, 429);
    return jsonResponse({ choices: [{ message: { role: 'assistant', content: 'Listo.' } }] });
  };

  const transcript = await runConversation({
    fetchImpl,
    apiKey: 'test-key',
    model: 'test-model',
    retryDelayMs: 0,
    maxRequestRetries: 1,
    scenario: { id: 'throttle', turns: [{ role: 'user', content: 'Hola' }] },
  });

  assert.equal(calls, 2);
  assert.equal(transcript.turns[0].response, 'Listo.');
});

test('evaluates repetition and internal payload leakage without calling a model', () => {
  const result = evaluateTranscript({
    model: 'test-model',
    scenarioId: 'quality',
    toolNames: [],
    turns: [
      { role: 'user', content: '¿Qué precio tiene la Ranger?' },
      { role: 'assistant', response: '{"query":"Ranger"}' },
      { role: 'user', content: '¿Y la Lobo?' },
      { role: 'assistant', response: 'La Ranger está disponible.' },
      { role: 'assistant', response: 'La Ranger está disponible.' },
    ],
  }, {
    id: 'quality',
    forbiddenText: ['"query"'],
    maxRepeatedResponses: 0,
  });

  assert.equal(result.passed, false);
  assert.ok(result.failures.some((failure) => failure.includes('internal payload')));
  assert.ok(result.failures.some((failure) => failure.includes('repeated response')));
});

test('evaluates continuity, topic changes, and grounding against the matching turn', () => {
  const scenario = {
    id: 'continuity',
    turns: [
      { requiredTools: ['search_vehicles'], requiredText: ['Lobo Raptor'] },
      { requiredTools: ['get_vehicle'], requiredText: ['km/l'], forbiddenText: ['¿qué modelo'] },
    ],
    requiredTools: ['search_vehicles', 'get_vehicle'],
    maxRepeatedResponses: 0,
  };
  const good = evaluateTranscript({
    model: 'fixture', scenarioId: scenario.id,
    toolNames: ['search_vehicles', 'get_vehicle'],
    turns: [
      { toolNames: ['search_vehicles'], response: 'La Lobo Raptor 2026.' },
      { toolNames: ['get_vehicle'], response: 'Rinde 7.1 km/l.' },
    ],
  }, scenario);
  const wrongTurn = evaluateTranscript({
    model: 'fixture', scenarioId: scenario.id,
    toolNames: ['search_vehicles', 'get_vehicle'],
    turns: [
      { toolNames: ['search_vehicles', 'get_vehicle'], response: 'La Lobo Raptor 2026.' },
      { toolNames: [], response: '¿Qué modelo te interesa?' },
    ],
  }, scenario);

  assert.equal(good.passed, true);
  assert.ok(wrongTurn.failures.some((failure) => failure.includes('turn 2 missing required tool')));
  assert.ok(wrongTurn.failures.some((failure) => failure.includes('turn 2 missing required text')));
  assert.ok(wrongTurn.failures.some((failure) => failure.includes('turn 2 contains forbidden text')));
});

test('counts materially repeated assistant responses despite small wording changes', () => {
  const result = evaluateTranscript({
    model: 'test-model', scenarioId: 'repetition', toolNames: [], turns: [
      { response: 'La Ranger está disponible en la agencia Ford Uruapan.' },
      { response: 'La Ranger está disponible en la agencia Ford Uruapan actualmente.' },
    ],
  }, { id: 'repetition', maxRepeatedResponses: 0 });

  assert.equal(result.passed, false);
  assert.ok(result.failures.some((failure) => failure.includes('repeated response')));
});

test('rejects unsupported catalog facts after a fixture source failure', () => {
  const failure = dispatchFixtureTool('get_vehicle', { slug: 'lobo-raptor-2026' }, {
    sourceFailureTools: ['get_vehicle'],
  });
  const scenario = {
    id: 'source-failure',
    turns: [{ requiredTools: ['get_vehicle'], requiredText: ['confirmar'], forbiddenText: ['7.1', 'km/l'] }],
    requiredTools: ['get_vehicle'],
    maxRepeatedResponses: 0,
  };

  assert.equal(failure.error, 'source_unavailable');
  assert.equal(evaluateTranscript({
    model: 'fixture', scenarioId: scenario.id, toolNames: ['get_vehicle'],
    turns: [{ toolNames: ['get_vehicle'], response: 'No puedo confirmar el rendimiento ahora.' }],
  }, scenario).passed, true);
  assert.equal(evaluateTranscript({
    model: 'fixture', scenarioId: scenario.id, toolNames: ['get_vehicle'],
    turns: [{ toolNames: ['get_vehicle'], response: 'Rinde 7.1 km/l.' }],
  }, scenario).passed, false);
});

test('fixture tools return bounded Ford data and reject unknown tools', () => {
  const vehicle = dispatchFixtureTool('get_vehicle', { slug: 'lobo-raptor-2026' }, {});

  assert.equal(vehicle.vehicle.model, 'Lobo Raptor');
  assert.equal(vehicle.vehicle.fuelEconomyKmPerLiter, 7.1);
  const offers = dispatchFixtureTool('list_promotions', { model: 'Ranger' }, {});
  assert.equal(offers.source, 'fixture_offers');
  assert.equal(offers.promotions[0].model, 'Ranger');
  assert.throws(() => dispatchFixtureTool('send_message', {}, {}), /read-only fixture tool/);
});

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return body; },
    async text() { return JSON.stringify(body); },
  };
}
