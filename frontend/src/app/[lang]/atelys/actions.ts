"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { sendForCompany } from "@/lib/api";
import { hasLocale } from "@/lib/i18n";

/**
 * One server action for the Atelys forms (ADR-0014). Hidden fields say where to send and where to go:
 * `_method`, `_path` (an Atelys API path only), `_back` (page of the form), `_next` (page after success, `{id}` replaced
 * by the created id). Body fields: `f.x` text, `n.x` number, `b.x` checkbox, `l.i.x` / `ln.i.x` text / number of
 * line i (under the key named by `_lines`), `csv.x` comma-separated list, `kv.i.x` "key=value; key=value" attributes of line i, `r.x` / `rn.x` one
 * residue. `_require` drops the lines without that field. `_list` sends the lines as the whole body. The backend checks everything again.
 */
export async function atelysAction(form: FormData) {
  const lang = String(form.get("lang") ?? "");
  if (!hasLocale(lang)) redirect("/");
  const method = String(form.get("_method") ?? "POST");
  // {name} in the path is replaced by the form field "name" (for ids and codes that belong in the URL).
  const path = String(form.get("_path") ?? "").replace(/\{(\w+)\}/g, (_, k: string) =>
    encodeURIComponent(String(form.get(k) ?? "")));
  const back = safe(lang, String(form.get("_back") ?? ""));
  if (!/^\/api\/v1\/(wms|gpao|atelys)\//.test(path) || !["POST", "PUT", "PATCH"].includes(method)) {
    redirect(`${back}${back.includes("?") ? "&" : "?"}error=VALIDATION_FAILED`);
  }
  const body: Record<string, unknown> = {};
  const lines = new Map<number, Record<string, unknown>>();
  const residue: Record<string, unknown> = {};
  for (const [key, raw] of form.entries()) {
    const value = typeof raw === "string" ? raw.trim() : "";
    const [kind, a, b] = key.split(".");
    if (key.startsWith("_") || key === "lang") continue;
    if (kind === "f" && value) body[a] = value;
    else if (kind === "n" && value) body[a] = Number(value.replace(",", "."));
    else if (kind === "b") body[a] = value === "on" || value === "true";
    // "csv.models" = "CK67, CK65" → ["CK67", "CK65"] (always sent, an empty list included).
    else if (kind === "csv") body[a] = value.split(",").map((v) => v.trim()).filter(Boolean);
    else if ((kind === "l" || kind === "ln" || kind === "kv" || kind === "lp") && b) {
      const line = lines.get(Number(a)) ?? {};
      if (kind === "lp" && value) {
        const keys = b.split("~");
        value.split("|").forEach((v, i) => {
          if (keys[i] && v) line[keys[i]] = v;
        });
      }
      if (kind === "l" && value) line[b] = value;
      if (kind === "ln" && value) line[b] = Number(value.replace(",", "."));
      if (kind === "kv" && value) line[b] = attributes(value);
      lines.set(Number(a), line);
    } else if (kind === "p" && value) {
      // "p.lotId~locationId" = "uuid|uuid": one select choosing a lot at a location.
      const keys = a.split("~");
      value.split("|").forEach((v, i) => {
        if (keys[i] && v) body[keys[i]] = v;
      });
    } else if (kind === "r" && value) residue[a] = value;
    else if (kind === "rn" && value) residue[a] = Number(value.replace(",", "."));
  }
  const filled = [...lines.entries()].sort((x, y) => x[0] - y[0]).map(([, l]) => l)
    .filter((l) => Object.keys(l).some((k) => k !== "unit" && k !== "attributes"))
    .filter((l) => !form.get("_require") || String(form.get("_require")) in l);
  const linesKey = String(form.get("_lines") ?? "");
  if (linesKey) body[linesKey] = filled;
  if (residue.quantity) body.residues = [residue];
  const payload = form.get("_list") ? filled : Object.keys(body).length ? body : null;

  const result = await sendForCompany<{ id?: string }>(method as "POST" | "PUT" | "PATCH", path, payload);
  revalidatePath(`/${lang}/atelys`, "layout");
  if (!result.ok) {
    redirect(`${back}${back.includes("?") ? "&" : "?"}error=${encodeURIComponent(result.errorCode)}`);
  }
  const next = String(form.get("_next") ?? "");
  const id = result.data && typeof result.data === "object" && "id" in result.data ? String(result.data.id) : "";
  if (next) redirect(safe(lang, next.replace("{id}", encodeURIComponent(id))));
  redirect(`${back}${back.includes("?") ? "&" : "?"}ok=1`);
}

/** "essence=Pin; humidite_pct=18" → { essence: "Pin", humidite_pct: 18 }. */
function attributes(text: string) {
  const map: Record<string, string | number> = {};
  for (const part of text.split(";")) {
    const [k, ...rest] = part.split("=");
    const key = k?.trim();
    const v = rest.join("=").trim();
    if (!key || !v) continue;
    map[key] = /^-?\d+([.,]\d+)?$/.test(v) ? Number(v.replace(",", ".")) : v;
  }
  return map;
}

function safe(lang: string, path: string) {
  const clean = path.split("?")[0];
  const query = path.includes("?") ? path.slice(path.indexOf("?")).replace(/([?&])(error|ok)=[^&]*/g, "$1") : "";
  return clean.startsWith(`/${lang}/atelys`) && !clean.startsWith("//") ? `${clean}${query.replace(/[?&]+$/, "")}` : `/${lang}/atelys`;
}
