import type { components } from "./schema";

export type Actor = components["schemas"]["Actor"];
export type Task = components["schemas"]["Task"];
export type TaskTypeOption = components["schemas"]["TaskType"];
export type TaskType = components["schemas"]["Type"];
export type TaskPage = components["schemas"]["TaskPage"];
export type Worker = components["schemas"]["Worker"];
export type History = components["schemas"]["History"];
export type Create = components["schemas"]["Create"];
export type Action = Task["actions"][number];

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(errorMessage(status, code));
  }
}

function errorMessage(status: number, code: string) {
  if (code === "VersionConflict")
    return "Задание изменилось. Обновите карточку и проверьте изменения перед повторным действием.";
  if (code === "IdempotencyConflict")
    return "Конфликт ключа действия. Обновите страницу и проверьте историю.";
  if (status === 401) return "Сессия истекла. Войдите снова.";
  if (status === 403)
    return "Действие недоступно: проверьте права или войдите заново.";
  if (status === 404) return "Запись не найдена.";
  if (status === 409) return "Состояние задания изменилось. Обновите карточку.";
  if (status === 400 || status === 422)
    return "Запрос отклонён. Проверьте поля формы и актуальность карточки.";
  return "API недоступен. Ответ не подтверждён; повторите то же действие, чтобы безопасно получить его результат.";
}

export class ApiClient {
  private csrf = "";
  sessionVersion = 0;
  onUnauthorized?: () => void;

  constructor(private transport: typeof fetch = (...args) => fetch(...args)) {}

  async request<T>(
    path: string,
    options: RequestInit = {},
    file = false,
  ): Promise<T> {
    const sessionVersion = this.sessionVersion;
    const headers = new Headers(options.headers);
    if (options.body) headers.set("Content-Type", "application/json");
    if (options.method && options.method !== "GET")
      headers.set("X-CSRF-TOKEN", this.csrf);
    let response: Response;
    try {
      response = await this.transport(`/api${path}`, {
        ...options,
        headers,
        credentials: "same-origin",
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === "AbortError")
        throw error;
      throw new ApiError(0, "Network");
    }
    const token = response.headers.get("X-CSRF-TOKEN");
    if (token && sessionVersion === this.sessionVersion) this.csrf = token;
    if (!response.ok) {
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      const code =
        typeof body === "object" &&
        body !== null &&
        "code" in body &&
        typeof body.code === "string"
          ? body.code
          : "Unknown";
      if (
        response.status === 401 &&
        path !== "/me" &&
        path !== "/session" &&
        sessionVersion === this.sessionVersion
      ) {
        this.sessionVersion++;
        this.onUnauthorized?.();
      }
      throw new ApiError(response.status, code);
    }
    if (response.status === 204) return undefined as T;
    if (file) return (await response.blob()) as T;
    try {
      return (await response.json()) as T;
    } catch {
      throw new ApiError(502, "InvalidResponse");
    }
  }

  me(signal?: AbortSignal) {
    return this.request<Actor>("/me", { signal });
  }
  async login(body: components["schemas"]["Login"]) {
    const actor = await this.request<Actor>("/session", {
      method: "POST",
      body: JSON.stringify(body),
    });
    this.sessionVersion++;
    return actor;
  }
  async logout() {
    try {
      await this.request<void>("/session", { method: "DELETE" });
      this.sessionVersion++;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401)
        this.sessionVersion++;
      throw error;
    }
  }
  tasks(type: string, page: number, signal?: AbortSignal) {
    const query = new URLSearchParams({ page: String(page), size: "20" });
    if (type) query.set("type", type);
    return this.request<TaskPage>(`/tasks?${query}`, { signal });
  }
  task(id: number, signal?: AbortSignal) {
    return this.request<Task>(`/tasks/${id}`, { signal });
  }
  taskTypes(signal?: AbortSignal) {
    return this.request<TaskTypeOption[]>("/task-types", { signal });
  }
  workers(signal?: AbortSignal) {
    return this.request<Worker[]>("/workers", { signal });
  }
  problems(signal?: AbortSignal) {
    return this.request<Task[]>("/problems", { signal });
  }
  history(id: number, signal?: AbortSignal) {
    return this.request<History[]>(`/tasks/${id}/history`, { signal });
  }
  file(id: number) {
    return this.request<Blob>(`/tasks/${id}/result/file`, {}, true);
  }
  create(body: Create, key: string) {
    return this.request<Task>("/tasks", {
      method: "POST",
      body: JSON.stringify(body),
      headers: { "Idempotency-Key": key },
    });
  }
  command(
    id: number,
    action: Action,
    body:
      | components["schemas"]["Edit"]
      | components["schemas"]["SelectWorker"]
      | components["schemas"]["Version"],
    key: string,
  ) {
    return this.request<Task>(
      `/tasks/${id}${action === "edit" ? "" : `/${action}`}`,
      {
        method:
          action === "edit" ? "PATCH" : action === "worker" ? "PUT" : "POST",
        body: JSON.stringify(body),
        headers: { "Idempotency-Key": key },
      },
    );
  }
}

export const api = new ApiClient();

export class ActionKeys {
  private keys = new Map<string, string>();
  constructor(private uuid: () => string = () => crypto.randomUUID()) {}
  get(fingerprint: string) {
    const existingKey = this.keys.get(fingerprint);
    if (existingKey) return existingKey;
    const key = this.uuid();
    this.keys.set(fingerprint, key);
    return key;
  }
  complete(fingerprint: string) {
    this.keys.delete(fingerprint);
  }
}
