#!/usr/bin/env node

const baseUrl = process.env.FORD_LOAD_BASE_URL ?? "http://127.0.0.1:8080";
const paths = (process.env.FORD_LOAD_PATHS ?? "/actuator/health")
  .split(",")
  .map((path) => path.trim())
  .filter(Boolean);
const requests = Number(process.env.FORD_LOAD_REQUESTS ?? 1000);
const concurrency = Number(process.env.FORD_LOAD_CONCURRENCY ?? 20);

if (!Number.isInteger(requests) || requests < 1 || !Number.isInteger(concurrency) || concurrency < 1) {
  throw new Error("FORD_LOAD_REQUESTS and FORD_LOAD_CONCURRENCY must be positive integers");
}

const percentile = (values, p) => values[Math.min(values.length - 1, Math.ceil(values.length * p) - 1)];

async function runPath(path) {
  const latencies = [];
  let completed = 0;
  let failures = 0;
  let next = 0;
  const wallStarted = performance.now();
  const worker = async () => {
    while (true) {
      const index = next++;
      if (index >= requests) return;
      const started = performance.now();
      try {
        const response = await fetch(new URL(path, baseUrl));
        await response.arrayBuffer();
        if (!response.ok) failures++;
      } catch {
        failures++;
      } finally {
        latencies.push(performance.now() - started);
        completed++;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, requests) }, worker));
  const wallDurationMs = performance.now() - wallStarted;
  latencies.sort((a, b) => a - b);
  return {
    path,
    requests: completed,
    failures,
    throughputPerSecond: Number((completed / (wallDurationMs / 1000)).toFixed(2)),
    p50Ms: Number(percentile(latencies, 0.50).toFixed(2)),
    p95Ms: Number(percentile(latencies, 0.95).toFixed(2)),
    p99Ms: Number(percentile(latencies, 0.99).toFixed(2)),
    maxMs: Number(Math.max(...latencies).toFixed(2)),
  };
}

const results = [];
for (const path of paths) results.push(await runPath(path));
console.log(JSON.stringify({ baseUrl, requests, concurrency, results }, null, 2));
if (results.some((result) => result.failures > 0)) process.exitCode = 1;
