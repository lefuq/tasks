import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TaskWorkspace } from "./TaskCard";
import { api, type Task, type History } from "../shared/api/client";
import { json, task, taskTypes } from "../test/fixtures";

function workspace(initial: Task, entries: History[] = []) {
  const cache = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  cache.setQueryData(["task-types"], taskTypes);
  const onRefresh = vi.fn();
  const view = (value: Task) => (
    <QueryClientProvider client={cache}>
      <TaskWorkspace
        task={value}
        workers={[{ id: 1, name: "Исполнитель А" }]}
        history={entries}
        onRefresh={onRefresh}
      />
    </QueryClientProvider>
  );
  const rendered = render(view(initial));
  return {
    cache,
    onRefresh,
    rerender: (value: Task) => rendered.rerender(view(value)),
    user: userEvent.setup(),
  };
}

describe("Карточка и серверные действия", () => {
  it("F-T004: не показывает подтверждённый переход до ответа", async () => {
    let reply!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            reply = resolve;
          }),
      ),
    );
    const { user, cache } = workspace(task({ actions: ["send"], workerId: 1 }));
    await user.click(screen.getByRole("button", { name: "Отправить" }));
    expect(screen.getByRole("status")).toHaveTextContent("ещё не подтверждено");
    expect(screen.queryByText("На исполнении")).not.toBeInTheDocument();
    expect(cache.getQueryData(["task", 10])).toBeUndefined();
    await act(async () =>
      reply(json(task({ state: "EXECUTING", version: 4, actions: [] }))),
    );
    await waitFor(() =>
      expect(cache.getQueryData<Task>(["task", 10])?.state).toBe("EXECUTING"),
    );
  });
  it("F-T005: сохраняет возвращённое задание без изменения полей", async () => {
    const transport = vi.fn().mockResolvedValue(json(task({ version: 4 })));
    vi.stubGlobal("fetch", transport);
    const { user } = workspace(task({ state: "RETURNED" }));
    await user.click(screen.getByRole("button", { name: "Сохранить" }));
    await waitFor(() => expect(transport).toHaveBeenCalledOnce());
    expect(transport.mock.calls[0]?.[0]).toBe("/api/tasks/10");
    expect(JSON.parse(transport.mock.calls[0]?.[1].body)).toEqual({
      version: 3,
      description: "",
      type: "process",
    });
  });
  it("F-T006: принимает fail с пустой data, после подтверждения редактирования нет", async () => {
    const completed = task({
      state: "COMPLETED",
      result: { id: "external", status: "fail", data: "", processingTime: 12 },
      actions: ["reconcile"],
      version: 4,
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(completed)));
    const { user, cache, rerender } = workspace({
      ...completed,
      state: "AWAITING_ACCEPTANCE",
      actions: ["accept"],
      version: 3,
    });
    expect(screen.getByText("fail")).toBeInTheDocument();
    expect(
      screen.getByText("Длительность исполнения: 12 секунд"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Принять результат" }));
    await waitFor(() =>
      expect(cache.getQueryData<Task>(["task", 10])?.state).toBe("COMPLETED"),
    );
    rerender(completed);
    expect(screen.getAllByText("Завершено").length).toBeGreaterThan(0);
    expect(
      screen.queryByRole("button", { name: "Сохранить" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Вернуть инициатору" }),
    ).not.toBeInTheDocument();
  });
  it("F-T007: безопасный повтор использует прежнее тело и ключ даже после обновления сервера", async () => {
    const transport = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("lost"))
      .mockResolvedValue(
        json(task({ state: "EXECUTING", version: 5, actions: ["retry"] })),
      );
    vi.stubGlobal("fetch", transport);
    const { user, rerender } = workspace(
      task({ actions: ["send"], workerId: 1 }),
    );
    await user.click(screen.getByRole("button", { name: "Отправить" }));
    await screen.findByRole("alert");
    rerender(task({ actions: ["send"], version: 4 }));
    await user.click(
      screen.getByRole("button", {
        name: "Повторить неподтверждённое действие",
      }),
    );
    await waitFor(() => expect(transport).toHaveBeenCalledTimes(2));
    const original = transport.mock.calls[0]?.[1];
    const repeated = transport.mock.calls[1]?.[1];
    expect(repeated.body).toBe(original.body);
    expect(new Headers(repeated.headers).get("Idempotency-Key")).toBe(
      new Headers(original.headers).get("Idempotency-Key"),
    );
  });
  it("F-T008: конфликт сохраняет введённый текст и запрещает повтор до обновления", async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(json({ code: "VersionConflict" }, 409));
    vi.stubGlobal("fetch", transport);
    const { user, rerender, onRefresh } = workspace(task());
    await user.type(screen.getByLabelText("Описание"), "Моя правка");
    rerender(task({ version: 4, description: "Чужая правка" }));
    await user.click(screen.getByRole("button", { name: "Сохранить" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Задание изменилось",
    );
    expect(screen.getByLabelText("Описание")).toHaveValue("Моя правка");
    expect(JSON.parse(transport.mock.calls[0]?.[1].body).version).toBe(3);
    expect(screen.getByRole("button", { name: "Сохранить" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Обновить карточку" }));
    expect(onRefresh).toHaveBeenCalledOnce();
    expect(transport).toHaveBeenCalledOnce();
  });
  it("F-T013: показывает утерю и разрешённую новую отправку, использует серверный список исполнителей", async () => {
    const transport = vi
      .fn()
      .mockResolvedValue(json(task({ workerId: 1, actions: ["send"] })));
    vi.stubGlobal("fetch", transport);
    const { user } = workspace(
      task({
        problem: "LOST_RESULT",
        exchange: "NEEDS_REVIEW",
        actions: ["worker", "send"],
      }),
    );
    expect(
      screen.getByRole("heading", { name: "Результат утерян" }),
    ).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Исполнитель"), "1");
    await user.click(
      screen.getByRole("button", { name: "Сохранить исполнителя" }),
    );
    await waitFor(() => expect(transport).toHaveBeenCalledOnce());
    expect(JSON.parse(transport.mock.calls[0]?.[1].body)).toEqual({
      version: 3,
      workerId: 1,
    });
  });
  it("показывает неопределённость и только разрешённые сервером действия", () => {
    workspace(task({ exchange: "UNKNOWN", actions: ["reconcile"] }));
    expect(screen.getByText(/задание могло быть принято/)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Запустить сверку" }),
    ).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "Отправить" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("Оставить открытой")).not.toBeInTheDocument();
  });
  it("не вставляет содержимое результата в страницу", () => {
    workspace(
      task({
        result: {
          id: "external",
          status: "success",
          processingTime: 12,
          data: btoa("<script>alert(1)</script>"),
        },
      }),
    );
    expect(document.querySelector("script")).toBeNull();
    expect(screen.queryByText(/alert\(1\)/)).not.toBeInTheDocument();
  });
  it("не использует отдельный обход API для файлов", async () => {
    const file = vi
      .spyOn(api, "file")
      .mockRejectedValue(new Error("Ошибка скачивания"));
    const { user } = workspace(
      task({
        result: {
          id: "external",
          status: "fail",
          processingTime: 12,
          data: "",
        },
      }),
    );
    await user.click(
      screen.getByRole("button", { name: "Скачать результат .md" }),
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Ошибка скачивания",
    );
    expect(file).toHaveBeenCalledWith(10);
  });
});

it("F-T016: расхождение и оба наблюдения видны до принятия подтверждённого fail", async () => {
  const original = {
    id: "external",
    status: "success" as const,
    data: "",
    processingTime: 12,
  };
  const confirmed = { ...original, status: "fail" as const };
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(async () =>
      json(
        task({
          state: "COMPLETED",
          result: confirmed,
          actions: ["reconcile"],
          version: 4,
        }),
      ),
    ),
  );
  const { user, cache } = workspace(
    task({
      problem: "MISMATCH",
      result: original,
      actions: ["accept", "reconcile"],
    }),
    [
      {
        id: 1,
        at: "2026-11-14T00:00:00Z",
        actorId: null,
        action: "reconciliation",
        details: JSON.stringify([
          "operation",
          JSON.stringify(original),
          JSON.stringify(confirmed),
        ]),
      },
    ],
  );
  expect(
    screen.getByRole("heading", { name: "Расхождение результатов" }),
  ).toBeInTheDocument();
  expect(screen.getByText("success", { exact: true })).toBeInTheDocument();
  await user.click(screen.getByText("Подробности события"));
  expect(screen.getByText(/"status": "success"/)).toHaveTextContent(
    '"status": "fail"',
  );
  expect(
    screen.getByText(/Показан первый сохранённый результат/),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Принять результат" }));
  await waitFor(() =>
    expect(cache.getQueryData<Task>(["task", 10])?.result?.status).toBe("fail"),
  );
});
