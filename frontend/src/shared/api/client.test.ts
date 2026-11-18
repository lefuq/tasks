import { describe, expect, it, vi } from "vitest";
import { ActionKeys, ApiClient, ApiError } from "./client";
import { json, task } from "../../test/fixtures";

describe("HTTP и ключи действий", () => {
  it("F-T007: повтор сохраняет ключ, завершение и новое действие получают новый", () => {
    let count = 0;
    const keys = new ActionKeys(() => `key-${++count}`);
    expect(keys.get("create:A")).toBe(keys.get("create:A"));
    expect(keys.get("create:B")).not.toBe(keys.get("create:A"));
    const key = keys.get("create:A");
    keys.complete("create:A");
    expect(keys.get("create:A")).not.toBe(key);
  });
  it("получает CSRF из 401 и обновляет после входа, передаёт cookie и версию", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(json({ code: "Unauthorized" }, 401, "before"))
      .mockResolvedValueOnce(json({ id: 1 }, 200, "after"))
      .mockResolvedValue(json(task()));
    const client = new ApiClient(transport);
    await expect(client.me()).rejects.toMatchObject({ status: 401 });
    await client.login({ username: "user", password: "pass" });
    expect(
      new Headers(transport.mock.calls[1]?.[1]?.headers).get("X-CSRF-TOKEN"),
    ).toBe("before");
    await client.command(10, "send", { version: 3 }, "action-key");
    const options = transport.mock.calls[2]?.[1];
    expect(options?.credentials).toBe("same-origin");
    expect(new Headers(options?.headers).get("X-CSRF-TOKEN")).toBe("after");
    expect(new Headers(options?.headers).get("Idempotency-Key")).toBe(
      "action-key",
    );
    expect(JSON.parse(String(options?.body))).toEqual({ version: 3 });
  });
  it.each([null, {}, { code: 7 }, "<html>failure</html>"])(
    "обрабатывает непроверенное тело ошибки %s",
    async (body) => {
      const client = new ApiClient(
        vi.fn<typeof fetch>().mockResolvedValue(json(body, 500)),
      );
      await expect(client.task(1)).rejects.toBeInstanceOf(ApiError);
      await expect(client.task(1)).rejects.toMatchObject({
        status: 500,
        code: "Unknown",
      });
    },
  );
  it("обрабатывает не-JSON, сеть и неверный успешный ответ", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response("oops", { status: 502 }))
      .mockRejectedValueOnce(new TypeError("fetch failed"))
      .mockResolvedValueOnce(new Response("oops"));
    const client = new ApiClient(transport);
    await expect(client.task(1)).rejects.toMatchObject({ status: 502 });
    await expect(client.task(1)).rejects.toMatchObject({ status: 0 });
    await expect(client.task(1)).rejects.toMatchObject({
      code: "InvalidResponse",
    });
  });
  it("передаёт отмену, не выдавая её за сетевую ошибку", async () => {
    const controller = new AbortController();
    const transport = vi
      .fn<typeof fetch>()
      .mockRejectedValue(new DOMException("cancelled", "AbortError"));
    const client = new ApiClient(transport);
    await expect(client.task(1, controller.signal)).rejects.toMatchObject({
      name: "AbortError",
    });
    expect(transport.mock.calls[0]?.[1]?.signal).toBe(controller.signal);
  });
  it("завершает сессию на 401 защищённого запроса", async () => {
    const client = new ApiClient(
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(json({ code: "Unauthorized" }, 401)),
    );
    client.onUnauthorized = vi.fn();
    await expect(client.tasks("research", 2)).rejects.toMatchObject({
      status: 401,
    });
    expect(client.onUnauthorized).toHaveBeenCalledOnce();
  });
  it("формирует пути, фильтр и методы по контракту", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => json(task()));
    const client = new ApiClient(transport);
    await client.tasks("research", 2);
    await client.problems();
    await client.workers();
    await client.history(10);
    await client.command(
      10,
      "edit",
      { version: 3, description: "", type: "collect" },
      "edit-key",
    );
    await client.command(
      10,
      "worker",
      { version: 3, workerId: 2 },
      "worker-key",
    );
    expect(transport.mock.calls.map((call) => call[0])).toEqual([
      "/api/tasks?page=2&size=20&type=research",
      "/api/problems",
      "/api/workers",
      "/api/tasks/10/history",
      "/api/tasks/10",
      "/api/tasks/10/worker",
    ]);
    expect(transport.mock.calls[4]?.[1]?.method).toBe("PATCH");
    expect(transport.mock.calls[5]?.[1]?.method).toBe("PUT");
  });
  it("выход без тела и скачивание файла проходят единый HTTP слой", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(""));
    const client = new ApiClient(transport);
    await expect(client.logout()).resolves.toBeUndefined();
    expect((await client.file(10)).size).toBe(0);
  });
});
