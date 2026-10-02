"use server";

import { isUuid, sendMappingCommand, type MappingRuleDto } from "@/lib/api";

type Outcome = { ok: true; version: number } | { ok: false; errorCode: string; fields?: { field: string; reason: string }[] };

function base(companyId: string, flowId: string) {
  if (!isUuid(companyId) || !isUuid(flowId)) throw new Error("Invalid identifiers");
  return `/api/v1/flows/${flowId}/mapping`;
}

async function run(companyId: string, method: "POST" | "PUT", path: string, body?: unknown): Promise<Outcome> {
  const result = await sendMappingCommand(companyId, method, path, body);
  return result.ok ? { ok: true, version: result.data.version } : { ok: false, errorCode: result.errorCode, fields: result.fields };
}

/** New draft, copied from the given rules (or from the published version when none). */
export async function createMappingDraft(companyId: string, flowId: string, rules: MappingRuleDto[] | null) {
  return run(companyId, "POST", `${base(companyId, flowId)}/versions`, rules === null ? undefined : { rules });
}

export async function saveMappingDraft(companyId: string, flowId: string, version: number, rules: MappingRuleDto[]) {
  return run(companyId, "PUT", `${base(companyId, flowId)}/versions/${Math.trunc(version)}`, { rules });
}

export async function testMappingVersion(companyId: string, flowId: string, version: number) {
  return run(companyId, "POST", `${base(companyId, flowId)}/versions/${Math.trunc(version)}/test`);
}

export async function publishMappingVersion(companyId: string, flowId: string, version: number) {
  return run(companyId, "POST", `${base(companyId, flowId)}/versions/${Math.trunc(version)}/publish`);
}
