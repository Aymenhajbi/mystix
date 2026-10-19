// Drives the Playwright MCP server (the one declared in .mcp.json) over stdio JSON-RPC, as Claude Code would,
// and runs the ediX checks: sign-in, key pages, accessibility of form controls, console errors.
// Usage: node e2e/mcp-run.mjs .claude/dev-credentials.txt <atelysCompanyId> <companyIdWithInvoices>.
// Credentials are never printed. Expected texts match the dev demo data (see the edix-business skill).
import { spawn } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";

const [credFile, company, invoiceCompany] = process.argv.slice(2);
const creds = Object.fromEntries(readFileSync(credFile, "utf8").split(/\r?\n/)
  .filter((l) => l.includes("=")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
mkdirSync("mcp", { recursive: true });

const server = spawn("cmd", ["/c", "npx", "-y", "@playwright/mcp@0.0.83", "--headless", "--output-dir", "mcp"], {
  stdio: ["pipe", "pipe", "inherit"],
});
let buffer = "";
const waiting = new Map();
server.stdout.on("data", (chunk) => {
  buffer += chunk;
  let i;
  while ((i = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, i).trim();
    buffer = buffer.slice(i + 1);
    if (!line) continue;
    const msg = JSON.parse(line);
    if (msg.id && waiting.has(msg.id)) {
      waiting.get(msg.id)(msg);
      waiting.delete(msg.id);
    }
  }
});
let next = 0;
const rpc = (method, params) => new Promise((resolve) => {
  const id = ++next;
  waiting.set(id, resolve);
  server.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
});
const tool = async (name, args = {}) => {
  const r = await rpc("tools/call", { name, arguments: args });
  if (r.error) throw new Error(`${name}: ${r.error.message}`);
  return (r.result.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("\n");
};
const ref = (snapshot, pattern) => snapshot.match(new RegExp(`${pattern}[^\\n]*\\[ref=([a-z0-9]+)\\]`))?.[1];

const results = [];
const check = (step, ok, evidence) => {
  results.push({ step, status: ok ? "OK" : "KO", evidence });
  console.log(`${ok ? "OK" : "KO"}  ${step}  | ${evidence}`);
};

await rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "edix-e2e", version: "1" } });
server.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");
const tools = (await rpc("tools/list", {})).result.tools.map((t) => t.name);
check("MCP tools/list", tools.includes("browser_navigate") && tools.includes("browser_snapshot"), `${tools.length} tools`);

// Sign-in through the MCP: the typed values are sent, the replies are not printed.
await tool("browser_navigate", { url: "http://localhost:3000/fr/login" });
// The login form is a client component: type only once it is hydrated, or React resets the fields.
await tool("browser_wait_for", { time: 2 });
let snap = await tool("browser_snapshot");
const email = ref(snap, 'textbox "Adresse e-mail"');
const password = ref(snap, 'textbox "Mot de passe"');
await tool("browser_type", { element: "Adresse e-mail", target: email, text: creds.email });
await tool("browser_type", { element: "Mot de passe", target: password, text: creds.password });
snap = await tool("browser_snapshot");
const submit = ref(snap, 'button "Se connecter"');
await tool("browser_click", { element: "Se connecter", target: submit });
await tool("browser_wait_for", { time: 2 });
await tool("browser_wait_for", { time: 2 });
snap = await tool("browser_snapshot");
console.log("after sign-in:", (snap.match(/Page URL: \S+/) ?? ["?"])[0], (snap.match(/alert[^\n]*/) ?? [""])[0].slice(0, 120));
check("Sign-in", /Page URL: http:\/\/localhost:3000\/fr\b/.test(snap) && !/fr\/login/.test(snap.match(/Page URL: \S+/)?.[0] ?? ""),
  snap.match(/Page URL: \S+/)?.[0] ?? "no url");


const pages = [
  ["Mystix cockpit: clearance simulated", "/fr", /Clearance simulée/],
  ["Invoices: simulated badge (company with invoices)", `/api/context?company=${invoiceCompany}&next=/fr/invoices`, /Simulée/],
  ["Atelys dashboard (demo company)", `/api/context?company=${company}&next=/fr/atelys`, /Industrie Bois Demo \(fictif\)/],
  ["Receipts: supplier dropdown", "/fr/atelys/receipts", /combobox "Fournisseur"/],
  ["Work orders: CMD reference", "/fr/atelys/work-orders", /CMD-\d{4}-\d{6}/],
  ["Terminaux: fleet", "/fr/atelys/devices", /CK67 quai 1 \(simulé\)/],
  ["Terminaux (ar)", "/ar/atelys/devices", /الأجهزة الطرفية/],
];
for (const [step, path, expected] of pages) {
  await tool("browser_navigate", { url: `http://localhost:3000${path}` });
  await tool("browser_wait_for", { time: 2 });
  snap = await tool("browser_snapshot");
  const onLogin = /Page URL: \S*\/login/.test(snap);
  const hit = onLogin ? undefined : snap.match(expected)?.[0];
  // Accessibility: form controls without an accessible name in the snapshot ("textbox [ref" has no name).
  const unnamed = (snap.match(/- (textbox|combobox|spinbutton|checkbox) \[ref=/g) ?? []).length;
  const consoleText = await tool("browser_console_messages", { onlyErrors: true }).catch(() => "");
  const errors = (consoleText.match(/\[ERROR\]/g) ?? []).length;
  check(step, Boolean(hit) && unnamed === 0 && errors === 0,
    `${hit ?? "expected text missing"} · unnamed controls ${unnamed} · console errors ${errors}`);
  writeFileSync(`mcp/${step.replace(/[^a-z0-9]+/gi, "-").toLowerCase()}.yml`, snap);
}
console.log("screenshot:", (await tool("browser_take_screenshot", { filename: "mcp-terminaux-ar.png", fullPage: true })).split("\n").filter((l) => /png/i.test(l)).join(" ").slice(0, 200));
await tool("browser_close");
writeFileSync("mcp/results.json", JSON.stringify(results, null, 2));
server.kill();
process.exit(results.some((r) => r.status === "KO") ? 1 : 0);
