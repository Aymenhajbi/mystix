/** Pages reachable without a session (ADR-0013). */
export const PUBLIC_PAGES = ["login", "setup"];

/** Set by the proxy: tells the layout which page renders, so sign-in pages get no portal shell. */
export const PATH_HEADER = "x-mystix-path";

/** True for /{lang}/login and /{lang}/setup. */
export function isPublicPath(pathname: string) {
  const page = pathname.split("/")[2] ?? "";
  return pathname.split("/").filter(Boolean).length === 2 && PUBLIC_PAGES.includes(page);
}

/** True for /{lang}/atelys and its pages (ADR-0014). */
export function isAtelysPath(pathname: string) {
  return /^\/(fr|ar)\/atelys(\/|$)/.test(pathname);
}
