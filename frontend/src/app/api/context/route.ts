import { NextResponse } from "next/server";
import { isUuid, switchSessionCompany } from "@/lib/api";

/**
 * Switches the environment of the session (ADR-0013), then redirects.
 * GET /api/context?company=<uuid>&next=/fr/clients/<uuid>
 * The backend refuses an environment the user does not belong to; the page then shows the current one.
 */
export async function GET(request: Request) {
  const url = new URL(request.url);
  const company = url.searchParams.get("company");
  const next = url.searchParams.get("next") ?? "/";
  // Only same-site relative paths: no open redirect.
  const target = next.startsWith("/") && !next.startsWith("//") ? next : "/";
  if (isUuid(company)) await switchSessionCompany(company);
  return NextResponse.redirect(new URL(target, url.origin));
}
