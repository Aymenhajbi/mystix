/** Base URL of the Mystix backend, read on the server. */
export const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type BackendHealth = { status: "UP" | "DOWN" | "UNREACHABLE" };

export async function fetchBackendHealth(): Promise<BackendHealth> {
  try {
    const response = await fetch(`${apiBaseUrl}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(3000),
    });
    const body = (await response.json()) as { status?: string };
    return { status: body.status === "UP" ? "UP" : "DOWN" };
  } catch {
    return { status: "UNREACHABLE" };
  }
}
