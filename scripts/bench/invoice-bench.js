// Throughput baseline of POST /api/v1/invoices on the local dev backend, with synthetic invoices only.
// Usage (backend in dev profile on 127.0.0.1:8080): node scripts/bench/invoice-bench.js → one JSON line per scenario, and bench-results.json in the current folder (git-ignored).
const fs = require('fs');
const path = require('path');

const API = 'http://127.0.0.1:8080';
const sample = JSON.parse(fs.readFileSync('C:/dev/mystix/backend/src/main/resources/samples/invoice-reference.request.json', 'utf8'));
const ICE = '000000099000099';
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Casablanca' }).format(new Date());
const run = Date.now().toString(36);

async function company() {
  const r = await fetch(`${API}/api/v1/companies`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ice: ICE, legalName: 'Banc de charge Synthetique SARL' }),
  });
  if (r.status === 201) return (await r.json()).id;
  // Already registered by an earlier run: find it.
  const list = await (await fetch(`${API}/api/v1/admin/environments`)).json();
  const found = list.find((e) => e.ice === ICE || e.company?.ice === ICE);
  if (!found) throw new Error('bench company not found, status ' + r.status);
  return found.id ?? found.company.id;
}

function invoice(n, lines) {
  const base = sample.lines;
  return {
    ...sample,
    number: `BENCH-${run}-${n}`,
    issueDate: today,
    dueDate: today,
    seller: { ...sample.seller, ice: ICE },
    lines: Array.from({ length: lines }, (_, i) => ({ ...base[i % base.length], id: String(i + 1) })),
  };
}

async function scenario(companyId, lines, concurrency, count) {
  const latencies = [];
  let next = 0;
  let ok = 0;
  const errors = {};
  const started = performance.now();
  async function worker() {
    while (next < count) {
      const n = next++;
      const body = JSON.stringify(invoice(`${lines}-${concurrency}-${n}`, lines));
      const t0 = performance.now();
      const r = await fetch(`${API}/api/v1/invoices`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Mystix-Company-Id': companyId },
        body,
      });
      const json = await r.json().catch(() => ({}));
      latencies.push(performance.now() - t0);
      if (r.ok) ok++;
      else errors[json.errorCode ?? r.status] = (errors[json.errorCode ?? r.status] ?? 0) + 1;
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker));
  const seconds = (performance.now() - started) / 1000;
  latencies.sort((a, b) => a - b);
  const pct = (p) => Math.round(latencies[Math.min(latencies.length - 1, Math.floor(p * latencies.length))]);
  return {
    lines,
    concurrency,
    count,
    ok,
    errors,
    seconds: +seconds.toFixed(1),
    invoicesPerHour: Math.round((ok / seconds) * 3600),
    linesPerHour: Math.round(((ok * lines) / seconds) * 3600),
    p50ms: pct(0.5),
    p95ms: pct(0.95),
    maxms: Math.round(latencies.at(-1)),
  };
}

(async () => {
  const companyId = await company();
  // Warm-up: the JVM compiles hot paths; the first requests are not representative.
  await scenario(companyId, 3, 4, 60);
  const plan = [
    [3, 1, 100], [3, 8, 300], [3, 32, 400],
    [20, 1, 60], [20, 8, 200], [20, 32, 240],
    [100, 1, 30], [100, 8, 80], [100, 32, 96],
    [500, 8, 24],
  ];
  const results = [];
  for (const [lines, concurrency, count] of plan) {
    const r = await scenario(companyId, lines, concurrency, count);
    console.log(JSON.stringify(r));
    results.push(r);
  }
  fs.writeFileSync(path.join(process.cwd(), 'bench-results.json'), JSON.stringify({ at: new Date().toISOString(), results }, null, 2));
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
