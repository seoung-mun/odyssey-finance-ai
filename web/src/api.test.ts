import { createApiClient, parseUnknown } from "./api";

describe("API client", () => {
  it("sends the in-memory token and does not retry a 401", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 401, code: "TOKEN_EXPIRED" }), {
        status: 401,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );
    const unauthorized = vi.fn();
    const client = createApiClient(() => "access-only-in-memory", unauthorized, fetcher);

    await expect(client.get("/dashboard", parseUnknown)).rejects.toMatchObject({ status: 401 });
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(new Headers(fetcher.mock.calls[0][1]?.headers).get("Authorization")).toBe(
      "Bearer access-only-in-memory",
    );
    expect(unauthorized).toHaveBeenCalledOnce();
  });

  it("preserves requestId from a 500 problem response", async () => {
    const client = createApiClient(
      () => null,
      vi.fn(),
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: 500, code: "INTERNAL", requestId: "req-7" }), {
          status: 500,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );

    await expect(client.get("/dashboard", parseUnknown)).rejects.toEqual(
      expect.objectContaining({ status: 500, requestId: "req-7" }),
    );
  });

  it("supports PATCH without changing the shared request rules", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ id: 4 }), { status: 200 }));
    const client = createApiClient(() => "access", vi.fn(), fetcher);

    await client.patch("/goals/4", { targetAmount: 10_000_000 }, parseUnknown);

    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/goals/4",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ targetAmount: 10_000_000 }),
      }),
    );
  });

  it("rejects a malformed successful response at the endpoint boundary", async () => {
    const client = createApiClient(
      () => null,
      vi.fn(),
      vi
        .fn()
        .mockResolvedValue(new Response(JSON.stringify({ id: "not-a-number" }), { status: 200 })),
    );

    await expect(
      client.get("/goal", (value) => {
        if (
          typeof value !== "object" ||
          value === null ||
          !("id" in value) ||
          typeof value.id !== "number"
        ) {
          throw new Error("INVALID_RESPONSE");
        }
        return value.id;
      }),
    ).rejects.toThrow("INVALID_RESPONSE");
  });
});
