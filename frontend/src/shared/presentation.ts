import type { Task } from "./api/client";

export const states: Record<Task["state"], string> = {
  NEW: "Новое",
  WAITING_TO_SEND: "Ожидает отправки",
  EXECUTING: "На исполнении",
  AWAITING_ACCEPTANCE: "Ожидает принятия",
  RETURNED: "Возвращено инициатору",
  COMPLETED: "Завершено",
};
export const exchanges = {
  PENDING: "Ожидает доставки",
  DELIVERING: "Доставляется",
  ACKNOWLEDGED: "Получение подтверждено",
  UNKNOWN: "Исход доставки неизвестен",
  RETRY_WAIT: "Ожидает повтора",
  NEEDS_REVIEW: "Требуется разбор",
};
export const problems = {
  TEMPORARY: "Временная ошибка связи",
  EXHAUSTED: "Повторы исчерпаны",
  INVALID_RESULT: "Технически невалидный результат",
  CONTRACT: "Нарушение контракта обмена",
  LOST_RESULT: "Результат утерян",
  MISMATCH: "Расхождение результатов",
};
export const actionNames = {
  edit: "Сохранить",
  worker: "Сохранить исполнителя",
  send: "Отправить",
  retry: "Повторить",
  return: "Вернуть инициатору",
  accept: "Принять результат",
  reconcile: "Запустить сверку",
};
export const historyNames: Record<string, string> = {
  created: "Создано",
  assigned: "Назначен оператор",
  edit: "Сохранено инициатором",
  worker: "Выбран исполнитель",
  send: "Отправка",
  retry: "Повтор",
  return: "Возврат инициатору",
  accept: "Результат принят",
  reconcile: "Сверка запрошена",
  reconciliation: "Результат сверки",
  problem: "Проблема обмена",
  exchange_attempt: "Попытка обмена",
  result_received: "Получен результат",
  result_observed: "Результат проверен",
  stale_response: "Ответ прежней операции",
  discarded_response: "Ответ отклонён",
};

export function localTime(
  utc: string,
  timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone,
) {
  return `${new Intl.DateTimeFormat("ru-RU", { timeZone, dateStyle: "short", timeStyle: "medium" }).format(new Date(utc))} (${timeZone})`;
}

const fieldNames: Record<string, string> = {
  id: "Номер задания",
  serviceId: "Служба",
  authorId: "Автор",
  operatorId: "Оператор",
  workerId: "Исполнитель",
  activeOperationId: "Операция",
  name: "Имя задания",
  description: "Описание",
  type: "Тип",
  state: "Состояние",
  version: "Версия",
};

function decodeDetails(value: unknown): unknown {
  if (
    typeof value === "string" &&
    (value.startsWith("{") || value.startsWith("["))
  ) {
    try {
      return decodeDetails(JSON.parse(value) as unknown);
    } catch {
      return value;
    }
  }
  if (Array.isArray(value)) return value.map(decodeDetails);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, decodeDetails(item)]),
    );
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function changedFields(
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  path = "",
  typeNames: Record<string, string> = {},
): string[] {
  return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(
    (key) => {
      const previous = before[key];
      const current = after[key];
      const label = path + (fieldNames[key] ?? key);
      if (isRecord(previous) && isRecord(current)) {
        return changedFields(previous, current, `${label}.`, typeNames);
      }
      if (JSON.stringify(previous) === JSON.stringify(current)) return [];
      const display = (value: unknown) => {
        if (key === "type" && typeof value === "string")
          return typeNames[value] ?? "—";
        if (key === "state" && typeof value === "string" && value in states) {
          return states[value as Task["state"]];
        }
        return value === undefined ? "—" : JSON.stringify(value);
      };
      return [
        `− ${label}: ${display(previous)}`,
        `+ ${label}: ${display(current)}`,
      ];
    },
  );
}

export function historyDetails(
  details: string,
  action?: string,
  typeNames: Record<string, string> = {},
): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(details) as unknown;
  } catch {
    return details;
  }
  const decoded = decodeDetails(parsed);
  if (action === "assigned" && typeof decoded === "number") {
    return `Назначен оператор #${decoded}`;
  }
  if (
    Array.isArray(decoded) &&
    decoded.length === 2 &&
    isRecord(decoded[0]) &&
    isRecord(decoded[1])
  ) {
    return (
      changedFields(decoded[0], decoded[1], "", typeNames).join("\n") ||
      "Поля задания не изменились."
    );
  }
  return typeof decoded === "string"
    ? decoded
    : JSON.stringify(
        decoded,
        (key, value: unknown) =>
          key === "type" && typeof value === "string"
            ? (typeNames[value] ?? "—")
            : value,
        2,
      );
}

export function saveFile(blob: Blob, id: number) {
  const url = URL.createObjectURL(
    new Blob([blob], { type: "application/octet-stream" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = `task-${id}.md`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
