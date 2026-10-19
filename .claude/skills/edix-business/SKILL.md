---
name: edix-business
description: >-
  ediX business rules for testing and reviewing Mystix (EDI, e-invoicing, simulated DGI clearance) and Atelys
  (GPAO, WMS, mobile terminals). Use when writing or running end-to-end tests of the portal with Playwright CLI or
  the Playwright MCP, when judging whether a screen or API result is correct, or when reporting test results.
---

# ediX business skill — Mystix and Atelys

ediX is the company; **Mystix** is its EDI and e-invoicing platform; **Atelys** ("powered by Mystix") adds production
(GPAO) and warehouse (WMS) management on the same backend. Architecture and rules: `AGENTS.md`, ADRs in
`docs/pmo/adr/`. This skill says what a correct result looks like and how to test it.

## Non-negotiable rules when testing

1. **Fictitious data only.** Company names, partners, products end with "(fictif)" / "(démo)" / "(simulé)". Never type
   a real customer, ICE or person, and nothing from the founder's former employers.
2. **Secrets.** Dev credentials live in `.claude/dev-credentials.txt` (`email=`, `password=`). Read them in a script,
   never print them, never paste them in a report, a screenshot caption or a command echoed to the console.
   Playwright CLI echoes the code it runs: run the password `fill` with its output discarded.
3. **DGI clearance is simulated** (ADR-0001). A correct screen says so ("Clearance simulée", "Simulée", references
   `SIMULATED`). A screen or report claiming a real DGI transmission is a **defect**. Never invent a DGI rule.
4. **Hardware.** A scan test marked "Simulé" never validates a terminal. Report "not tested on a Honeywell terminal"
   unless a real device was used.
5. **Report honestly**: livré (seen working in this run), prévu, hypothèse. Quote what was checked (URL, element,
   value). A test you could not run is "non vérifié", not "OK".

## Invariants to check

| Area | Expected |
| --- | --- |
| Tenant isolation | The environment chip (top bar) names the active company; data of another company never appears. |
| Idempotence | Confirming a receipt twice records stock once; a resent mobile operation (`Idempotency-Key`) returns the same receipt. |
| Numbering | Receipts `REC-YYYY-NNNNNN`, work orders `OF-YYYY-NNNNNN`, customer reference of a work order `CMD-YYYY-NNNNNN` generated, never typed. |
| Amounts | MAD with 2 decimals; quantities keep their unit (U, KG…). |
| Errors | User-facing message in French or Arabic, never a stack trace or a raw code alone. |
| i18n | `/fr/...` and `/ar/...` both render; Arabic is right-to-left. |
| Accessibility | Every form control has a label; the current nav item has `aria-current="page"`. |

## Portal map (dev: http://localhost:3000)

- Login `/fr/login`. Mystix: `/fr` (dashboard), `/fr/invoices`, `/fr/invoices/new`, `/fr/clients`, `/fr/connectivity`,
  `/fr/jobs`, `/fr/logs`, `/fr/mapping`, `/fr/flow`, `/fr/stock`.
- Atelys: `/fr/atelys` (dashboard), `work-orders`, `boms`, `stock`, `receipts`, `transfers`, `inventories`,
  `shipments`, `traceability`, `channels`, `devices` (Terminaux), `devices/profiles`, `settings`.
- Switch company: `/api/context?company=<uuid>&next=<path>`. Demo company "Industrie Bois Demo (fictif) SARL".

## Reference scenario (end to end)

1. Sign in; land on the Mystix dashboard; the clearance tile says simulated.
2. Invoices: list loads; an invoice detail shows the simulated clearance badge.
3. Connectivity and jobs pages load without error.
4. Switch to the Atelys demo company; dashboard loads.
5. Receipts: the supplier is a dropdown fed by partners (type SUPPLIER); create a draft with one line, confirm it;
   the receipt shows "Confirmée" and a lot.
6. Work orders: the form shows "Référence commande — générée à la création"; create a draft OF; its detail shows a
   `CMD-` reference.
7. Bills of materials: no redundant "Nouvelle nomenclature" header button; the form is on the page.
8. Terminaux: fleet tiles and cards; a device page shows journey, reasons, scan tests tagged Réel/Simulé, history.
9. Arabic: `/ar/atelys/devices` renders right-to-left.
10. Collect browser console errors on every page; any error is reported with the page.

## Report template

Per step: status (OK / KO / non vérifié), evidence (URL, element text), screenshot file. Then: defects found
(severity, steps), what was simulated, what was not tested (hardware, real DGI, external partners).
