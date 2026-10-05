"use client";

import { useRef, useState, useTransition } from "react";
import styles from "@/app/[lang]/portal.module.css";
import { createInvoiceAction, vatRatesAction, type InvoiceDraft } from "@/app/[lang]/invoices/actions";
import type { Dictionary } from "@/app/[lang]/dictionaries";
import type { ApiErrorBody, Partner } from "@/lib/api";

type Labels = Dictionary["newInvoice"];
type Line = InvoiceDraft["lines"][number];

/** EN 16931 rules whose fix is a form field: the summary links to it. */
const RULE_FIELDS: Record<string, string> = {
  "BR-S-02": "seller.taxIdentifier",
  "BR-Z-02": "seller.taxIdentifier",
};

const UNIT_CODES = ["C62", "H87", "KGM", "LTR", "MTR", "HUR", "DAY"] as const;

const emptyLine = (from?: Line): Line => ({
  itemName: "",
  quantity: "1",
  unitCode: from?.unitCode ?? "C62",
  unitPrice: "",
  // The user types the rate; the previous line's choice is carried over, never a built-in value.
  vatCategory: from?.vatCategory ?? "S",
  vatRate: from?.vatRate ?? "",
});

/** Field id for a backend path such as "lines[0].unitPrice" or "buyer.address.city". */
const fieldId = (path: string) => `f-${path.replace(/[^a-zA-Z0-9]+/g, "-")}`;

export function InvoiceForm({
  lang,
  labels,
  seller,
  customers,
  today,
  initialRates,
  enforceVatRates,
}: {
  lang: "fr" | "ar";
  labels: Labels;
  seller: { legalName: string; ice: string; taxIdentifier: string | null };
  customers: Partner[];
  today: string;
  /** Standard rates in force on the issue date (dated referential); empty when it has none. */
  initialRates: string[];
  enforceVatRates: boolean;
}) {
  const [rates, setRates] = useState<string[]>(initialRates);
  const [, startRates] = useTransition();
  const changeIssueDate = (value: string) => {
    set("issueDate", value);
    startRates(async () => setRates(await vatRatesAction(value)));
  };
  const [draft, setDraft] = useState<InvoiceDraft>({
    number: "",
    issueDate: today,
    dueDate: "",
    currency: "MAD",
    buyerReference: "",
    purchaseOrderReference: "",
    note: "",
    seller: { taxIdentifier: seller.taxIdentifier ?? "", tradeRegister: "", street: "", city: "", postalCode: "" },
    buyer: { name: "", ice: "", gln: "", street: "", city: "", postalCode: "", countryCode: "MA" },
    lines: [emptyLine()],
  });
  const [error, setError] = useState<ApiErrorBody | null>(null);
  const [pending, startTransition] = useTransition();
  const summary = useRef<HTMLDivElement>(null);

  const fieldErrors = new Map((error?.fieldErrors ?? []).map((f) => [f.field, f.reason]));

  const set = <K extends keyof InvoiceDraft>(key: K, value: InvoiceDraft[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const setSeller = (key: keyof InvoiceDraft["seller"], value: string) =>
    setDraft((d) => ({ ...d, seller: { ...d.seller, [key]: value } }));
  const setBuyer = (key: keyof InvoiceDraft["buyer"], value: string) =>
    setDraft((d) => ({ ...d, buyer: { ...d.buyer, [key]: value } }));
  const setLine = (index: number, patch: Partial<Line>) =>
    setDraft((d) => ({ ...d, lines: d.lines.map((l, i) => (i === index ? { ...l, ...patch } : l)) }));

  const pickCustomer = (id: string) => {
    const partner = customers.find((p) => p.id === id);
    if (!partner) return;
    setDraft((d) => ({ ...d, buyer: { ...d.buyer, name: partner.name, ice: partner.ice ?? "", gln: partner.gln ?? "" } }));
  };

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    startTransition(async () => {
      // On success the action redirects to the invoice; it only returns on failure.
      const result = await createInvoiceAction(lang, draft);
      setError(result.error);
      requestAnimationFrame(() => summary.current?.focus());
    });
  };

  const pathLabel = (path: string): string => {
    const line = /^lines\[(\d+)\]\.?(.*)$/.exec(path);
    if (line) {
      const key = line[2] as keyof Labels["paths"];
      return `${labels.line} ${Number(line[1]) + 1} · ${labels.paths[key] ?? line[2]}`;
    }
    return labels.paths[path as keyof Labels["paths"]] ?? path;
  };

  /** One text input bound to a backend path, with its error. */
  const input = (
    path: string,
    label: string,
    value: string,
    onChange: (v: string) => void,
    extra: React.InputHTMLAttributes<HTMLInputElement> = {},
  ) => {
    const id = fieldId(path);
    const problem = fieldErrors.get(path);
    // The error sits outside the <label> so it is not read as part of the field name; aria-describedby links it.
    return (
      <div className={styles.field}>
        <label htmlFor={id}>{label}</label>
        <input
          id={id}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `${id}-error` : undefined}
          {...extra}
        />
        {problem && (
          <small id={`${id}-error`} className={styles.fieldError}>
            {labels.invalid} <span className={styles.mono}>({problem})</span>
          </small>
        )}
      </div>
    );
  };

  /**
   * Rate of a standard-rated line: a list of the rates in force on the issue date when the environment checks
   * them, else a free field with those rates as suggestions. Nothing is preselected: the user chooses.
   */
  const rateField = (i: number, line: Line) => {
    const path = `lines[${i}].vat.ratePercent`;
    if (!(enforceVatRates && rates.length > 0)) {
      return input(path, labels.vatRate, line.vatRate, (v) => setLine(i, { vatRate: v }), {
        required: true,
        inputMode: "decimal",
        dir: "ltr",
        list: rates.length > 0 ? "vat-rates" : undefined,
      });
    }
    const id = fieldId(path);
    const problem = fieldErrors.get(path);
    return (
      <div className={styles.field}>
        <label htmlFor={id}>{labels.vatRate}</label>
        <select
          id={id}
          value={rates.includes(line.vatRate) ? line.vatRate : ""}
          onChange={(e) => setLine(i, { vatRate: e.target.value })}
          required
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? `${id}-error` : `vat-rates-help`}
        >
          <option value="">{labels.pickRate}</option>
          {rates.map((r) => (
            <option key={r} value={r}>
              {r} %
            </option>
          ))}
        </select>
        {problem && (
          <small id={`${id}-error`} className={styles.fieldError}>
            {labels.invalid} <span className={styles.mono}>({problem})</span>
          </small>
        )}
      </div>
    );
  };

  return (
    <form onSubmit={submit} className={styles.stack} noValidate>
      {error && (
        <div ref={summary} tabIndex={-1} className={styles.errorSummary} role="alert" aria-labelledby="invoice-error-title">
          <h2 id="invoice-error-title">{error.userMessage?.[lang] ?? labels.errors[error.errorCode as keyof Labels["errors"]] ?? labels.errors.default}</h2>
          {error.suggestedAction && <p>{error.suggestedAction[lang]}</p>}
          {error.fieldErrors.length > 0 && (
            <ul>
              {error.fieldErrors.map((f) => (
                <li key={f.field}>
                  <a href={`#${fieldId(f.field)}`}>{pathLabel(f.field)}</a>{" "}
                  <span className={styles.mono}>{f.reason}</span>
                </li>
              ))}
            </ul>
          )}
          {error.ruleViolations.length > 0 && (
            <ul>
              {error.ruleViolations.map((r, i) => (
                <li key={`${r.ruleId}-${i}`}>
                  {RULE_FIELDS[r.ruleId] ? (
                    <a href={`#${fieldId(RULE_FIELDS[r.ruleId])}`}>{pathLabel(RULE_FIELDS[r.ruleId])}</a>
                  ) : null}{" "}
                  <b className={styles.mono}>{r.ruleId}</b> {r.message}
                </li>
              ))}
            </ul>
          )}
          {error.requestId && (
            <p className={styles.mono}>
              {labels.requestId} {error.requestId}
            </p>
          )}
        </div>
      )}

      <section className={styles.panel} aria-labelledby="inv-head">
        <div className={styles.panelHead}>
          <h2 id="inv-head">{labels.invoice}</h2>
        </div>
        <div className={styles.form}>
          <div className={styles.formRow}>
            {input("number", labels.number, draft.number, (v) => set("number", v), { required: true, maxLength: 100, placeholder: labels.numberPlaceholder, dir: "ltr" })}
            {input("issueDate", labels.issueDate, draft.issueDate, changeIssueDate, { type: "date", required: true })}
            {input("dueDate", labels.dueDate, draft.dueDate, (v) => set("dueDate", v), { type: "date" })}
            {input("currency", labels.currency, draft.currency, (v) => set("currency", v), { required: true, maxLength: 3, pattern: "[A-Za-z]{3}", dir: "ltr" })}
          </div>
        </div>
      </section>

      <div className={styles.gridTwo}>
        <section className={styles.panel} aria-labelledby="inv-seller">
          <div className={styles.panelHead}>
            <h2 id="inv-seller">{labels.seller}</h2>
            <span className={styles.hint}>{labels.sellerHint}</span>
          </div>
          <div className={styles.form}>
            <dl className={styles.locked}>
              <div>
                <dt>{labels.legalName}</dt>
                <dd>{seller.legalName}</dd>
              </div>
              <div>
                <dt>ICE</dt>
                <dd className={styles.mono}>{seller.ice}</dd>
              </div>
            </dl>
            <div className={styles.formRow}>
              {input("seller.taxIdentifier", labels.taxIdentifier, draft.seller.taxIdentifier, (v) => setSeller("taxIdentifier", v), { required: true, maxLength: 20, dir: "ltr" })}
              {input("seller.tradeRegister", labels.tradeRegister, draft.seller.tradeRegister, (v) => setSeller("tradeRegister", v), { maxLength: 50, dir: "ltr" })}
            </div>
            {input("seller.address.street", labels.street, draft.seller.street, (v) => setSeller("street", v), { maxLength: 255 })}
            <div className={styles.formRow}>
              {input("seller.address.city", labels.city, draft.seller.city, (v) => setSeller("city", v), { maxLength: 100 })}
              {input("seller.address.postalCode", labels.postalCode, draft.seller.postalCode, (v) => setSeller("postalCode", v), { maxLength: 20, dir: "ltr" })}
            </div>
          </div>
        </section>

        <section className={styles.panel} aria-labelledby="inv-buyer">
          <div className={styles.panelHead}>
            <h2 id="inv-buyer">{labels.buyer}</h2>
          </div>
          <div className={styles.form}>
            {customers.length > 0 && (
              <label className={styles.field}>
                <span>{labels.pickCustomer}</span>
                <select defaultValue="" onChange={(e) => pickCustomer(e.target.value)}>
                  <option value="">{labels.manualCustomer}</option>
                  {customers.map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.name}
                      {p.ice ? ` · ICE ${p.ice}` : ""}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {input("buyer.name", labels.buyerName, draft.buyer.name, (v) => setBuyer("name", v), { required: true, maxLength: 255 })}
            <div className={styles.formRow}>
              {input("buyer.ice", labels.buyerIce, draft.buyer.ice, (v) => setBuyer("ice", v), { inputMode: "numeric", maxLength: 15, dir: "ltr" })}
              {input("buyer.gln", labels.gln, draft.buyer.gln, (v) => setBuyer("gln", v), { inputMode: "numeric", maxLength: 13, dir: "ltr" })}
            </div>
            <div className={styles.formRow}>
              {input("buyer.address.city", labels.city, draft.buyer.city, (v) => setBuyer("city", v), { maxLength: 100 })}
              {input("buyer.address.countryCode", labels.country, draft.buyer.countryCode, (v) => setBuyer("countryCode", v), { required: true, maxLength: 2, dir: "ltr" })}
            </div>
          </div>
        </section>
      </div>

      <section className={styles.panel} aria-labelledby="inv-lines">
        <div className={styles.panelHead}>
          <h2 id="inv-lines">{labels.lines}</h2>
          <span className={styles.hint}>{labels.linesHint}</span>
        </div>
        <div className={styles.form}>
          {draft.lines.map((line, i) => (
            <fieldset key={i} className={styles.lineSet}>
              <legend>
                {labels.line} {i + 1}
              </legend>
              <div className={styles.lineGrid}>
                {input(`lines[${i}].itemName`, labels.itemName, line.itemName, (v) => setLine(i, { itemName: v }), { required: true, maxLength: 255 })}
                {input(`lines[${i}].quantity`, labels.quantity, line.quantity, (v) => setLine(i, { quantity: v }), { required: true, inputMode: "decimal", dir: "ltr" })}
                <label className={styles.field}>
                  <span>{labels.unit}</span>
                  <select value={line.unitCode} onChange={(e) => setLine(i, { unitCode: e.target.value })}>
                    {UNIT_CODES.map((code) => (
                      <option key={code} value={code}>
                        {labels.units[code]} ({code})
                      </option>
                    ))}
                  </select>
                </label>
                {input(`lines[${i}].unitPrice`, labels.unitPrice, line.unitPrice, (v) => setLine(i, { unitPrice: v }), { required: true, inputMode: "decimal", dir: "ltr" })}
                <label className={styles.field}>
                  <span>{labels.vatCategory}</span>
                  <select
                    value={line.vatCategory}
                    onChange={(e) => setLine(i, { vatCategory: e.target.value === "Z" ? "Z" : "S" })}
                  >
                    <option value="S">{labels.vatS}</option>
                    <option value="Z">{labels.vatZ}</option>
                  </select>
                </label>
                {line.vatCategory === "S"
                  ? rateField(i, line)
                  : (
                    <p className={styles.formNote} style={{ alignSelf: "end" }}>
                      {labels.vatZNote}
                    </p>
                  )}
              </div>
              {draft.lines.length > 1 && (
                <button
                  type="button"
                  className={styles.btn}
                  onClick={() => setDraft((d) => ({ ...d, lines: d.lines.filter((_, j) => j !== i) }))}
                >
                  {labels.removeLine} {i + 1}
                </button>
              )}
            </fieldset>
          ))}
          <div>
            <button
              type="button"
              className={styles.btn}
              onClick={() => setDraft((d) => ({ ...d, lines: [...d.lines, emptyLine(d.lines[d.lines.length - 1])] }))}
            >
              {labels.addLine}
            </button>
          </div>
          <datalist id="vat-rates">
            {rates.map((r) => (
              <option key={r} value={r} />
            ))}
          </datalist>
          <p id="vat-rates-help" className={styles.formNote} style={{ margin: 0 }}>
            {rates.length > 0
              ? labels.ratesInForce.replace("{date}", draft.issueDate).replace("{rates}", rates.map((r) => `${r} %`).join(", "))
              : labels.noRates}
          </p>
        </div>
      </section>

      <section className={styles.panel} aria-labelledby="inv-refs">
        <div className={styles.panelHead}>
          <h2 id="inv-refs">{labels.references}</h2>
        </div>
        <div className={styles.form}>
          <div className={styles.formRow}>
            {input("buyerReference", labels.buyerReference, draft.buyerReference, (v) => set("buyerReference", v), { maxLength: 100 })}
            {input("purchaseOrderReference", labels.purchaseOrderReference, draft.purchaseOrderReference, (v) => set("purchaseOrderReference", v), { maxLength: 100 })}
          </div>
          <label className={styles.field} htmlFor={fieldId("note")}>
            <span>{labels.note}</span>
            <textarea id={fieldId("note")} value={draft.note} maxLength={1000} rows={3} onChange={(e) => set("note", e.target.value)} />
          </label>
        </div>
      </section>

      <div className={styles.formActions}>
        <button type="submit" className={`${styles.btn} ${styles.btnPrimary}`} disabled={pending} aria-busy={pending}>
          {pending ? labels.submitting : labels.submit}
        </button>
        <p className={styles.formNote} style={{ margin: 0 }}>
          {labels.submitNote}
        </p>
      </div>
    </form>
  );
}
