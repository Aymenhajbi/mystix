// One-minute sustained run: 20-line invoices, 16 concurrent callers. Run invoice-bench.js first: it registers the bench company.
const fs = require('fs');
const API = 'http://127.0.0.1:8080';
const sample = JSON.parse(fs.readFileSync('C:/dev/mystix/backend/src/main/resources/samples/invoice-reference.request.json', 'utf8'));
const ICE = '000000099000099';
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Casablanca' }).format(new Date());
const run = 'S' + Date.now().toString(36);
const DURATION_MS = 60000;
const LINES = 20;
const CONCURRENCY = 16;

(async () => {
  const list = await (await fetch(`${API}/api/v1/admin/environments`)).json();
  const env = list.find((e) => (e.ice ?? e.company?.ice) === ICE);
  const companyId = env.id ?? env.company.id;
  const end = performance.now() + DURATION_MS;
  let n = 0, ok = 0, failed = 0;
  const lat = [];
  const perSecond = new Map();
  async function worker() {
    while (performance.now() < end) {
      const i = n++;
      const body = JSON.stringify({
        ...sample, number: `${run}-${i}`, issueDate: today, dueDate: today, seller: { ...sample.seller, ice: ICE },
        lines: Array.from({ length: LINES }, (_, k) => ({ ...sample.lines[k % 3], id: String(k + 1) })),
      });
      const t0 = performance.now();
      const r = await fetch(`${API}/api/v1/invoices`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Mystix-Company-Id': companyId }, body });
      await r.arrayBuffer();
      lat.push(performance.now() - t0);
      if (r.ok) { ok++; const s = Math.floor((performance.now() - (end - DURATION_MS)) / 1000); perSecond.set(s, (perSecond.get(s) ?? 0) + 1); } else failed++;
    }
  }
  const t = performance.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, worker));
  const seconds = (performance.now() - t) / 1000;
  lat.sort((a, b) => a - b);
  const p = (q) => Math.round(lat[Math.floor(q * (lat.length - 1))]);
  const rates = [...perSecond.values()];
  console.log(JSON.stringify({
    lines: LINES, concurrency: CONCURRENCY, seconds: +seconds.toFixed(1), ok, failed,
    invoicesPerHour: Math.round(ok / seconds * 3600), linesPerHour: Math.round(ok * LINES / seconds * 3600),
    p50ms: p(0.5), p95ms: p(0.95), p99ms: p(0.99), minPerSecond: Math.min(...rates), maxPerSecond: Math.max(...rates), run,
  }));
})();
