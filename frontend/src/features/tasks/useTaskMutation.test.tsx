import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { api } from "../../shared/api/client";
import { json, task } from "../../test/fixtures";
import { useTaskMutation } from "./useTaskMutation";

it("ответ команды прежней сессии не заполняет кэш после выхода", async () => {
  let reply!: (response: Response) => void;
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      url === "/api/session"
        ? Promise.resolve(new Response(null, { status: 204 }))
        : new Promise<Response>((resolve) => {
            reply = resolve;
          }),
    ),
  );
  const cache = new QueryClient();
  const { result } = renderHook(
    () =>
      useTaskMutation("send", (_body: number, key) =>
        api.command(10, "send", { version: 3 }, key),
      ),
    {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={cache}>{children}</QueryClientProvider>
      ),
    },
  );
  act(() => result.current.mutate(3));
  await waitFor(() => expect(result.current.isPending).toBe(true));
  await api.logout();
  await act(async () => reply(json(task({ version: 4 }))));
  await waitFor(() => expect(result.current.isSuccess).toBe(true));
  expect(cache.getQueryData(["task", 10])).toBeUndefined();
});
