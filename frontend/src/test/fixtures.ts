import type { Actor, Task } from "../shared/api/client";

export const taskTypes = [
  { code: "process", name: "Обработка" },
  { code: "research", name: "Исследование" },
  { code: "collect", name: "Сбор" },
];

export const initiator: Actor = {
  id: 1,
  serviceId: 1,
  role: "INITIATOR",
  name: "Инициатор",
};
export const operator: Actor = {
  id: 2,
  serviceId: 1,
  role: "OPERATOR",
  name: "Оператор",
};
export function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 10,
    name: "Задание-1",
    description: "",
    type: "process",
    typeName: "Обработка",
    state: "WAITING_TO_SEND",
    version: 3,
    authorId: 1,
    operatorId: 2,
    workerId: null,
    operationId: null,
    exchange: null,
    problem: null,
    externalId: null,
    result: null,
    actions: ["edit"],
    ...overrides,
  };
}

export function json(body: unknown, status = 200, token = "csrf-test") {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "X-CSRF-TOKEN": token },
  });
}
