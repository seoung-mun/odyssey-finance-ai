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
export type Parser<T> = (value: unknown) => T;
export const parseUnknown = (value: unknown) => value;

export const createApiClient = (
  getToken: () => string | null,
  onUnauthorized: () => void,
  fetcher: typeof fetch = fetch,
) => {
  const request = async (
    path: string,
    parser: Parser<unknown>,
    init: RequestInit = {},
  ): Promise<unknown> => {
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
    if (response.status === 204) return parser(undefined);
    const body: unknown = await response.json().catch(() => ({}));
    if (!response.ok) {
      const problem = parseProblem(body);
      if (response.status === 401) onUnauthorized();
      throw new ApiError(response.status, problem.code, problem.detail, problem.requestId);
    }
    return parser(body);
  };

  return {
    get: (path: string, parser: Parser<unknown>) => request(path, parser),
    post: (path: string, body: unknown, parser: Parser<unknown>) =>
      request(path, parser, {
        method: "POST",
        body: body === undefined ? undefined : JSON.stringify(body),
      }),
    put: (path: string, body: unknown, parser: Parser<unknown>) =>
      request(path, parser, { method: "PUT", body: JSON.stringify(body) }),
    patch: (path: string, body: unknown, parser: Parser<unknown>) =>
      request(path, parser, { method: "PATCH", body: JSON.stringify(body) }),
    delete: (path: string, parser: Parser<unknown>) =>
      request(path, parser, { method: "DELETE" }),
  };
};

const parseProblem = (value: unknown): Problem => {
  if (typeof value !== "object" || value === null) return {};
  return {
    status: "status" in value && typeof value.status === "number" ? value.status : undefined,
    code: "code" in value && typeof value.code === "string" ? value.code : undefined,
    detail: "detail" in value && typeof value.detail === "string" ? value.detail : undefined,
    requestId:
      "requestId" in value && typeof value.requestId === "string" ? value.requestId : undefined,
  };
};
