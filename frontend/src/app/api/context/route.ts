import { NextResponse } from "next/server";
import { COMPANY_COOKIE, isUuid } from "@/lib/api";

/**
 * Selects the active client environment, then redirects.
 * GET /api/context?company=<uuid>&next=/fr/clients/<uuid>
 * TODO(auth): replaced by the authenticated principal's environments (Lot 8).
 */
export async function GET(request: Request) {
  const url = new URL(request.url);
  const company = url.searchParams.get("company");
  const next = url.searchParams.get("next") ?? "/";
  // Only same-site relative paths: no open redirect.
  const target = next.startsWith("/") && !next.startsWith("//") ? next : "/";
  const response = NextResponse.redirect(new URL(target, url.origin));
  if (isUuid(company)) {
    response.cookies.set(COMPANY_COOKIE, company, {
      httpOnly: true,
      sameSite: "lax",
      path: "/",
      maxAge: 60 * 60 * 24 * 30,
    });
  }
  return response;
}
