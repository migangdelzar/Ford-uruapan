import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const DEFAULT_BASE_URL = 'https://openrouter.ai/api/v1';
const DEFAULT_MAX_TOOL_ROUNDS = 3;
const DEFAULT_REQUEST_TIMEOUT_MS = 45_000;

const TOOL_DEFINITIONS = [
  tool('search_vehicles', 'Busca modelos y versiones Ford en el catálogo vigente.', {
    text: { type: 'string' }, modelYear: { type: 'integer' },
  }, ['text']),
  tool('get_vehicle', 'Consulta los detalles confirmados de un vehículo.', {
    slug: { type: 'string' }, text: { type: 'string' },
  }, []),
  tool('compare_vehicles', 'Compara dos vehículos Ford con atributos confirmados.', {
    slugs: { type: 'array', items: { type: 'string' } },
  }, ['slugs']),
  tool('search_offers', 'Busca ofertas vigentes relacionadas con una necesidad.', {
    query: { type: 'string' },
  }, ['query']),
  tool('list_promotions', 'Consulta promociones vigentes.', {
    model: { type: 'string' },
  }, []),
  tool('get_vehicle_provenance', 'Consulta el respaldo de un dato del catálogo.', {
    slug: { type: 'string' }, field: { type: 'string' },
  }, ['slug', 'field']),
  tool('get_catalog_status', 'Verifica la disponibilidad y vigencia del catálogo.', {}, []),
  tool('approved_knowledge_search', 'Busca conocimiento aprobado de la agencia.', {
    query: { type: 'string' },
  }, ['query']),
  tool('vehicle_snapshot_search', 'Busca un respaldo local de vehículos.', {
    text: { type: 'string' },
  }, ['text']),
];

const DEFAULT_SYSTEM_PROMPT = `
Eres un asesor humano de Ford Uruapan. Responde en español de México con calidez, naturalidad y precisión.
La pregunta más reciente tiene prioridad absoluta. Usa el historial para no repetir saludos, preguntas o respuestas.
Consulta herramientas de solo lectura para datos variables de vehículos, promociones y ofertas.
Usa los resultados de las herramientas como fuente de verdad; no inventes datos.
Las herramientas son silenciosas: nunca muestres JSON, argumentos, nombres de herramientas, proveedores o razonamiento.
Responde primero la pregunta actual y haz como máximo una pregunta útil.
`;

export async function runConversation({
  fetchImpl = globalThis.fetch,
  baseUrl = DEFAULT_BASE_URL,
  apiKey,
  model,
  scenario,
  maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS,
  requestTimeoutMs = DEFAULT_REQUEST_TIMEOUT_MS,
  maxRequestRetries = 2,
  retryDelayMs = 1_000,
}) {
  if (typeof fetchImpl !== 'function') {
    throw new TypeError('fetch implementation is required');
  }
  if (!apiKey) {
    throw new Error('OpenRouter API key is required');
  }
  if (!model) {
    throw new Error('model is required');
  }
  if (!scenario?.id || !Array.isArray(scenario.turns) || scenario.turns.length === 0) {
    throw new Error('scenario must contain an id and at least one turn');
  }

  const messages = [{
    role: 'system',
    content: scenario.systemPrompt ?? DEFAULT_SYSTEM_PROMPT,
  }];
  const transcript = {
    model,
    scenarioId: scenario.id,
    toolNames: [],
    turns: [],
  };

  for (const turn of scenario.turns) {
    if (turn.role !== 'user') {
      throw new Error(`fixture turn must be a user message: ${scenario.id}`);
    }
    messages.push({ role: 'user', content: turn.content });
    const turnResult = {
      role: 'user',
      content: turn.content,
      toolNames: [],
      response: null,
    };
    let toolRounds = 0;

    while (true) {
      const response = await fetchWithRetry(fetchImpl, `${baseUrl.replace(/\/$/, '')}/chat/completions`, {
        method: 'POST',
        headers: {
          authorization: `Bearer ${apiKey}`,
          'content-type': 'application/json',
        },
          body: JSON.stringify({
          model,
          messages,
          tools: TOOL_DEFINITIONS,
          tool_choice: 'auto',
          temperature: 0.2,
            max_tokens: 500,
          }),
          signal: AbortSignal.timeout(requestTimeoutMs),
      }, { maxRequestRetries, retryDelayMs });
      if (!response.ok) {
        throw new Error(`OpenRouter request failed with HTTP ${response.status}`);
      }
      const body = await response.json();
      const message = body?.choices?.[0]?.message;
      if (!message) {
        throw new Error('OpenRouter response did not contain a message');
      }

      if (Array.isArray(message.tool_calls) && message.tool_calls.length > 0) {
        toolRounds += 1;
        if (toolRounds > maxToolRounds) {
          throw new Error(`tool round limit exceeded for ${scenario.id}`);
        }
        messages.push({
          role: 'assistant',
          content: message.content ?? null,
          tool_calls: message.tool_calls,
        });
        for (const toolCall of message.tool_calls) {
          const name = toolCall?.function?.name;
          const args = parseArguments(toolCall?.function?.arguments);
          const result = dispatchFixtureTool(name, args, scenario);
          turnResult.toolNames.push(name);
          transcript.toolNames.push(name);
          messages.push({
            role: 'tool',
            tool_call_id: toolCall.id,
            name,
            content: JSON.stringify(result),
          });
        }
        continue;
      }

      const content = typeof message.content === 'string' ? message.content.trim() : '';
      if (!content) {
        throw new Error(`OpenRouter returned an empty final response for ${scenario.id}`);
      }
      turnResult.response = content;
      transcript.turns.push(turnResult);
      messages.push({ role: 'assistant', content });
      break;
    }
  }

  return transcript;
}

export function dispatchFixtureTool(name, args = {}, scenario = {}) {
  if (!TOOL_DEFINITIONS.some((definition) => definition.function.name === name)) {
    throw new Error(`not a read-only fixture tool: ${name}`);
  }
  if (scenario.sourceFailureTools?.includes(name)) {
    return { error: 'source_unavailable', message: 'No se pudo confirmar la fuente en esta prueba.' };
  }
  const requested = String(args.slug ?? args.text ?? args.query ?? args.model ?? '').toLowerCase();
  if (name === 'search_vehicles' || name === 'vehicle_snapshot_search') {
    return {
      source: 'fixture_catalog',
      vehicles: [
        { slug: 'lobo-raptor-2026', model: 'Lobo Raptor', year: 2026, version: 'Raptor' },
        { slug: 'ranger-xlt-2026', model: 'Ranger', year: 2026, version: 'XLT' },
      ].filter((vehicle) => !requested || `${vehicle.model} ${vehicle.version} ${vehicle.year}`
        .toLowerCase().includes(requested.replaceAll('-', ' ')) || requested.includes(vehicle.model.toLowerCase())),
    };
  }
  if (name === 'get_vehicle') {
    return {
      source: 'fixture_catalog',
      vehicle: {
        slug: 'lobo-raptor-2026', model: 'Lobo Raptor', year: 2026, version: 'Raptor',
        fuelEconomyKmPerLiter: 7.1, engine: '3.5L EcoBoost V6',
      },
    };
  }
  if (name === 'compare_vehicles') {
    return { source: 'fixture_catalog', vehicles: [
      { model: 'Ranger', version: 'XLT', use: 'trabajo y uso diario' },
      { model: 'Lobo Raptor', version: 'Raptor', use: 'alto desempeño todoterreno' },
    ] };
  }
  if (name === 'list_promotions' || name === 'search_offers') {
    return { source: 'fixture_offers', promotions: [
      { model: 'Ranger', title: 'Oferta de ejemplo', validity: 'Vigencia incluida en la ficha vigente' },
    ] };
  }
  if (name === 'approved_knowledge_search') {
    return { source: 'fixture_knowledge', results: [
      { title: 'Atención Ford Uruapan', text: 'Lunes a sábado de 9:00 a.m. a 7:00 p.m.' },
    ] };
  }
  if (name === 'get_vehicle_provenance') {
    return { source: 'fixture_catalog', slug: args.slug ?? 'lobo-raptor-2026', field: args.field ?? 'vehicle' };
  }
  if (name === 'get_catalog_status') {
    return { source: 'fixture_catalog', status: 'available', updatedAt: 'fixture' };
  }
  return { source: 'fixture_catalog', result: [] };
}

export function evaluateTranscript(transcript, scenario) {
  const failures = [];
  const responses = (transcript.turns ?? [])
    .map((turn) => turn.response)
    .filter((response) => typeof response === 'string' && response.trim());
  const allText = responses.join('\n').toLowerCase();
  for (const marker of scenario.forbiddenText ?? []) {
    if (allText.includes(marker.toLowerCase())) {
      failures.push(`internal payload or forbidden text: ${marker}`);
    }
  }
  for (const required of scenario.requiredText ?? []) {
    if (!allText.includes(required.toLowerCase())) {
      failures.push(`missing required text: ${required}`);
    }
  }
  for (const requiredTool of scenario.requiredTools ?? []) {
    if (!(transcript.toolNames ?? []).includes(requiredTool)) {
      failures.push(`missing required tool: ${requiredTool}`);
    }
  }
  const normalizedResponses = responses.map(normalizeResponse);
  const duplicates = normalizedResponses.length - new Set(normalizedResponses).size;
  if (duplicates > (scenario.maxRepeatedResponses ?? 0)) {
    failures.push(`repeated response count: ${duplicates}`);
  }
  return {
    model: transcript.model,
    scenarioId: transcript.scenarioId,
    toolNames: transcript.toolNames ?? [],
    responses,
    failures,
    passed: failures.length === 0,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const fixturePath = path.resolve(options.fixture ?? path.join(
    path.dirname(fileURLToPath(import.meta.url)), 'fixtures/human-friendly-conversations.json'));
  const scenarios = JSON.parse(await fs.readFile(fixturePath, 'utf8'));
  const apiKey = process.env.OPENROUTER_API_KEY;
  if (!apiKey) {
    console.log('SKIPPED: set OPENROUTER_API_KEY to run the live OpenRouter evaluator.');
    return;
  }
  const models = (options.models ?? process.env.OPENROUTER_MODELS ?? 'openrouter/free')
    .split(',').map((model) => model.trim()).filter(Boolean);
  const results = [];
  for (const model of models) {
    for (const scenario of scenarios) {
      const started = Date.now();
      try {
        const transcript = await runConversation({
          apiKey, model, scenario,
          baseUrl: options.baseUrl ?? DEFAULT_BASE_URL,
          maxToolRounds: Number(options.maxToolRounds ?? DEFAULT_MAX_TOOL_ROUNDS),
          requestTimeoutMs: Number(options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS),
          maxRequestRetries: Number(options.maxRequestRetries ?? 2),
          retryDelayMs: Number(options.retryDelayMs ?? 1_000),
        });
        const result = evaluateTranscript(transcript, scenario);
        results.push({ ...result, latencyMs: Date.now() - started });
      } catch (error) {
        results.push({ model, scenarioId: scenario.id, toolNames: [], responses: [],
          failures: [`runtime error: ${error.message}`], passed: false, latencyMs: Date.now() - started });
      }
    }
  }
  const output = path.resolve(options.output ?? 'build/reports/openrouter-chat-eval');
  await fs.mkdir(output, { recursive: true });
  await fs.writeFile(path.join(output, 'results.json'), `${JSON.stringify(results, null, 2)}\n`);
  await fs.writeFile(path.join(output, 'summary.md'), renderSummary(results));
  console.log(renderSummary(results).trim());
  if (results.some((result) => !result.passed)) {
    process.exitCode = 1;
  }
}

async function fetchWithRetry(fetchImpl, url, init, { maxRequestRetries, retryDelayMs }) {
  for (let attempt = 0; ; attempt += 1) {
    const response = await fetchImpl(url, init);
    if (response.status !== 429 || attempt >= maxRequestRetries) return response;
    const retryAfter = Number(response.headers?.get?.('retry-after'));
    const delayMs = Number.isFinite(retryAfter) ? Math.min(retryAfter * 1_000, 60_000) : retryDelayMs;
    if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
}

function tool(name, description, properties, required) {
  return { type: 'function', function: {
    name, description, parameters: { type: 'object', properties, required, additionalProperties: false },
  } };
}

function parseArguments(value) {
  if (!value) return {};
  try {
    return JSON.parse(value);
  } catch {
    throw new Error('model returned invalid tool arguments');
  }
}

function normalizeResponse(value) {
  return value.toLowerCase().replaceAll(/\s+/g, ' ').trim();
}

function parseArgs(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    if (!args[index].startsWith('--')) continue;
    const key = args[index].slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    options[key] = args[index + 1]?.startsWith('--') ? true : args[index + 1];
    if (options[key] !== true) index += 1;
  }
  return options;
}

function renderSummary(results) {
  const passed = results.filter((result) => result.passed).length;
  const lines = [`# OpenRouter chat evaluation`, ``, `${passed}/${results.length} passed`, ``];
  for (const result of results) {
    lines.push(`- ${result.passed ? 'PASS' : 'FAIL'} \`${result.model}\` / \`${result.scenarioId}\` (${result.latencyMs}ms)`);
    for (const failure of result.failures ?? []) lines.push(`  - ${failure}`);
  }
  return `${lines.join('\n')}\n`;
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isMain) {
  main().catch((error) => {
    console.error(`OpenRouter evaluator failed: ${error.message}`);
    process.exitCode = 1;
  });
}
