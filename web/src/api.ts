export type Problem = {
  status?: number;
  code?: string;
  detail?: string;
  requestId?: string;
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code = "HTTP_ERROR",
    detail?: string,
    readonly requestId?: string,
  ) {
    super(detail ?? code);
  }
}

export type ApiClient = ReturnType<typeof createApiClient>;

export function createApiClient(
  getToken: () => string | null,
  onUnauthorized: () => void,
  fetcher: typeof fetch = fetch,
) {
  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = getToken();
    const headers = new Headers(init.headers);
    if (token) headers.set("Authorization", `Bearer ${token}`);
    if (init.body) headers.set("Content-Type", "application/json");

    let response: Response;
    try {
      response = await fetcher(`/api/v1${path}`, { ...init, headers, credentials: "include" });
    } catch {
      throw new ApiError(0, "NETWORK_ERROR", "네트워크 연결을 확인해 주세요.");
    }
    if (response.status === 204) return undefined as T;
    const body = await response.json().catch(() => ({} as Problem));
    if (!response.ok) {
      const problem = body as Problem;
      if (response.status === 401) onUnauthorized();
      throw new ApiError(response.status, problem.code, problem.detail, problem.requestId);
    }
    return body as T;
  }

  return {
    get: <T>(path: string) => request<T>(path),
    post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
    put: <T>(path: string, body: unknown) => request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  };
}
