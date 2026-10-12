import { NextResponse, type NextRequest } from "next/server";
import { defaultLocale, locales } from "@/lib/i18n";
import { isPublicPath, PATH_HEADER } from "@/lib/routes";

/** Same name as SESSION_COOKIE in lib/api.ts (that module is server-only, the proxy cannot import it). */
const SESSION_COOKIE = "mystix-session";

/**
 * Sends paths without a locale to /fr or /ar, from the browser's Accept-Language; sends visitors without a session
 * to the sign-in page, keeping where they wanted to go. The session itself is checked by the backend on each call.
 */
export function proxy(request: NextRequest) {
  const { pathname, search } = request.nextUrl;
  const lang = locales.find((l) => pathname === `/${l}` || pathname.startsWith(`/${l}/`));
  if (!lang) {
    request.nextUrl.pathname = `/${preferredLocale(request)}${pathname === "/" ? "" : pathname}`;
    return NextResponse.redirect(request.nextUrl);
  }

  if (!isPublicPath(pathname) && !request.cookies.get(SESSION_COOKIE)?.value) {
    const login = request.nextUrl.clone();
    login.pathname = `/${lang}/login`;
    login.search = `?next=${encodeURIComponent(pathname + search)}`;
    return NextResponse.redirect(login);
  }

  const headers = new Headers(request.headers);
  headers.set(PATH_HEADER, pathname);
  return NextResponse.next({ request: { headers } });
}

function preferredLocale(request: NextRequest) {
  const accept = request.headers.get("accept-language") ?? "";
  const first = accept.split(",")[0]?.trim().toLowerCase() ?? "";
  return first.startsWith("ar") ? "ar" : defaultLocale;
}

export const config = {
  matcher: ["/((?!_next|api|favicon.ico).*)"],
};
