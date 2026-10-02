import type { LogStat } from "./api";

/**
 * Turns processing-log aggregates into the stages of the real invoice pipeline:
 * API → fields and canonical mapping → idempotence → UBL 2.1 + XSD → EN 16931 → storage → clearance (simulated).
 * Counts come only from the log; nothing is estimated.
 */
export type StageKey = "reception" | "fields" | "idempotence" | "ubl" | "en16931" | "storage" | "clearance";

export type Stage = {
  key: StageKey;
  /** Items that reached the stage in the window. */
  input: number;
  /** Items that left the stage towards the next one. */
  passed: number;
  /** Items stopped at this stage. */
  rejected: number;
  /** Items that left the pipeline here without being an error (replays). */
  diverted: number;
  lastAt: string | null;
};

export type Flow = {
  stages: Stage[];
  outcomes: { cleared: number; rejected: number; replays: number; clearanceErrors: number };
};

export const windows = ["PT1H", "PT24H", "P7D"] as const;
export type FlowWindow = (typeof windows)[number];
export const isFlowWindow = (v: string | undefined): v is FlowWindow =>
  v !== undefined && (windows as readonly string[]).includes(v);

export function buildFlow(stats: LogStat[]): Flow {
  const pick = (predicate: (s: LogStat) => boolean) => stats.filter(predicate);
  const sum = (items: LogStat[]) => items.reduce((n, s) => n + s.count, 0);
  const last = (items: LogStat[]) =>
    items.reduce<string | null>((latest, s) => (latest === null || s.lastAt > latest ? s.lastAt : latest), null);

  const rejected = pick((s) => s.event === "INVOICE_REJECTED");
  const internal = rejected.filter((s) => s.errorCode === "INTERNAL_ERROR");
  const conflicts = rejected.filter((s) => s.errorCode === "INVOICE_NUMBER_CONFLICT");
  const generation = rejected.filter((s) => s.errorCode === "INVOICE_SCHEMA_INVALID");
  const rules = rejected.filter((s) => s.stage === "VALIDATION");
  const fields = rejected.filter(
    (s) => !internal.includes(s) && !conflicts.includes(s) && !generation.includes(s) && !rules.includes(s),
  );
  const replays = pick((s) => s.event === "INVOICE_REPLAYED");
  const accepted = pick((s) => s.event === "INVOICE_ACCEPTED");
  const cleared = pick((s) => s.event === "CLEARANCE_CLEARED");
  const clearanceRejected = pick((s) => s.event === "CLEARANCE_REJECTED");
  const clearanceErrors = pick((s) => s.event === "CLEARANCE_ERROR");

  const received = sum(rejected) + sum(replays) + sum(accepted);
  const stages: Stage[] = [];
  const push = (key: StageKey, input: number, stopped: number, diverted: number, items: LogStat[]) => {
    const passed = Math.max(0, input - stopped - diverted);
    stages.push({ key, input, passed, rejected: stopped, diverted, lastAt: last(items) });
    return passed;
  };

  let flowing = push("reception", received, sum(internal), 0, [...rejected, ...replays, ...accepted]);
  flowing = push("fields", flowing, sum(fields), 0, fields.length ? fields : accepted);
  flowing = push("idempotence", flowing, sum(conflicts), sum(replays), [...conflicts, ...replays]);
  flowing = push("ubl", flowing, sum(generation), 0, generation.length ? generation : accepted);
  push("en16931", flowing, sum(rules), 0, rules.length ? rules : accepted);
  push("storage", sum(accepted), 0, 0, accepted);
  stages.push({
    key: "clearance",
    input: sum(cleared) + sum(clearanceRejected) + sum(clearanceErrors),
    passed: sum(cleared),
    rejected: sum(clearanceRejected) + sum(clearanceErrors),
    diverted: 0,
    lastAt: last([...cleared, ...clearanceRejected, ...clearanceErrors]),
  });

  return {
    stages,
    outcomes: {
      cleared: sum(cleared),
      rejected: sum(rejected) + sum(clearanceRejected),
      replays: sum(replays),
      clearanceErrors: sum(clearanceErrors),
    },
  };
}
