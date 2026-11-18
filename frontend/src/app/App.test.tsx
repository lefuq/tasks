import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { App } from "./App";
import { initiator, json, operator, task, taskTypes } from "../test/fixtures";

it("F-T001: вход показывает пользователя и службу, выход скрывает защищённые данные", async () => {
  let authenticated = false;
  const transport = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === "/api/session" && options?.method === "POST") {
      authenticated = true;
      return json(initiator);
    }
    if (url === "/api/session" && options?.method === "DELETE") {
      authenticated = false;
      return new Response(null, { status: 204 });
    }
    if (url === "/api/task-types") return json(taskTypes);
    if (url === "/api/me")
      return authenticated
        ? json(initiator)
        : json({ code: "Unauthorized" }, 401);
    return json({ items: [task()], total: 1, page: 0, size: 20 });
  });
  vi.stubGlobal("fetch", transport);
  history.replaceState(null, "", "/tasks");
  render(<App />);
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Логин"), "initiator1");
  await user.type(screen.getByLabelText("Пароль"), "password");
  await user.click(screen.getByRole("button", { name: "Войти" }));
  expect(
    await screen.findByRole("link", { name: "Задание-1" }),
  ).toBeInTheDocument();
  expect(
    screen.getByText(/Инициатор · Инициатор · Служба № 1/),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Выйти" }));
  expect(
    await screen.findByRole("heading", { name: "Вход в службу" }),
  ).toBeInTheDocument();
  expect(
    screen.queryByRole("link", { name: "Задание-1" }),
  ).not.toBeInTheDocument();
});

it("F-T003: фильтр сбрасывает страницу, пагинация следует API", async () => {
  const transport = vi.fn(async (url: string) => {
    if (url === "/api/task-types") return json(taskTypes);
    if (url === "/api/me") return json(initiator);
    const page = Number(new URL(url, "http://local").searchParams.get("page"));
    return json({
      items: [task({ type: "research" })],
      total: 21,
      page,
      size: 20,
    });
  });
  vi.stubGlobal("fetch", transport);
  history.replaceState(null, "", "/tasks");
  render(<App />);
  const user = userEvent.setup();
  await screen.findByRole("option", { name: "Исследование" });
  await user.selectOptions(screen.getByLabelText("Тип задания"), "research");
  await waitFor(() =>
    expect(transport).toHaveBeenCalledWith(
      "/api/tasks?page=0&size=20&type=research",
      expect.anything(),
    ),
  );
  await user.click(screen.getByRole("button", { name: "Далее" }));
  expect(await screen.findByText("Страница 2 · Всего 21")).toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("Тип задания"), "collect");
  expect(await screen.findByText("Страница 1 · Всего 21")).toBeInTheDocument();
});

it("истёкшая сессия удаляет реестр и показывает вход", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      url === "/api/me" ? json(initiator) : json({ code: "Unauthorized" }, 401),
    ),
  );
  history.replaceState(null, "", "/tasks");
  render(<App />);
  expect(
    await screen.findByRole("heading", { name: "Вход в службу" }),
  ).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("Сессия истекла");
  expect(
    screen.queryByRole("navigation", { name: "Основная навигация" }),
  ).not.toBeInTheDocument();
});

it("оператору недоступна форма создания, даже по прямому адресу", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => json(operator)),
  );
  history.replaceState(null, "", "/tasks/new");
  render(<App />);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Создание доступно только инициатору",
  );
  expect(
    screen.queryByRole("button", { name: "Создать задание" }),
  ).not.toBeInTheDocument();
});

it("список проблем использует API и не подменяет их локальным набором", async () => {
  const transport = vi.fn(async (url: string) =>
    url === "/api/me" ? json(operator) : json([task({ problem: "MISMATCH" })]),
  );
  vi.stubGlobal("fetch", transport);
  history.replaceState(null, "", "/problems");
  render(<App />);
  expect(
    await screen.findByRole("link", { name: "Задание-1" }),
  ).toBeInTheDocument();
  expect(screen.getByText("Расхождение результатов")).toBeInTheDocument();
  expect(transport).toHaveBeenCalledWith("/api/problems", expect.anything());
});

it("создание после потери ответа повторяет сохранённый запрос и открывает подтверждённую карточку", async () => {
  let createAttempts = 0;
  const transport = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === "/api/task-types") return json(taskTypes);
    if (url === "/api/me") return json(initiator);
    if (url === "/api/tasks" && options?.method === "POST") {
      createAttempts++;
      if (createAttempts === 1) throw new TypeError("lost response");
      return json(task(), 201);
    }
    if (url === "/api/workers" || url.endsWith("/history")) return json([]);
    return json(task());
  });
  vi.stubGlobal("fetch", transport);
  history.replaceState(null, "", "/tasks/new");
  render(<App />);
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Имя задания"), "Задание-1");
  await user.click(screen.getByRole("button", { name: "Создать задание" }));
  await user.click(
    await screen.findByRole("button", {
      name: "Повторить неподтверждённое действие",
    }),
  );
  expect(
    await screen.findByRole("heading", { name: "Задание-1" }),
  ).toBeInTheDocument();
  const requests = transport.mock.calls.filter(
    (call) => call[1]?.method === "POST",
  );
  expect(requests).toHaveLength(2);
  expect(new Headers(requests[0]?.[1]?.headers).get("Idempotency-Key")).toBe(
    new Headers(requests[1]?.[1]?.headers).get("Idempotency-Key"),
  );
});
