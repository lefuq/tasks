export interface paths {
  "/api/session": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["login"];
    delete: operations["logout"];
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/me": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };

    get: operations["me"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/workers": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get: operations["workers"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/task-types": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get: operations["taskTypes"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };

    get: operations["tasks"];
    put?: never;
    post: operations["createTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get: operations["task"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;

    patch: operations["editTask"];
    trace?: never;
  };
  "/api/tasks/{id}/worker": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put: operations["selectWorker"];
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/history": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get: operations["history"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/result/file": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get: operations["downloadFile"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/problems": {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };

    get: operations["problems"];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/send": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["sendTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/retry": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["retryTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/return": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["returnTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/accept": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put?: never;
    post: operations["acceptTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  "/api/tasks/{id}/reconcile": {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    get?: never;
    put?: never;

    post: operations["reconcileTask"];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    Type: "process" | "research" | "collect";

    State:
      | "NEW"
      | "WAITING_TO_SEND"
      | "EXECUTING"
      | "AWAITING_ACCEPTANCE"
      | "RETURNED"
      | "COMPLETED";

    Exchange:
      | "PENDING"
      | "DELIVERING"
      | "ACKNOWLEDGED"
      | "UNKNOWN"
      | "RETRY_WAIT"
      | "NEEDS_REVIEW"
      | null;

    Problem:
      | "TEMPORARY"
      | "EXHAUSTED"
      | "INVALID_RESULT"
      | "CONTRACT"
      | "LOST_RESULT"
      | "MISMATCH"
      | null;
    Actor: {
      id: number;

      serviceId: number;

      role: "INITIATOR" | "OPERATOR";
      name: string;
    };
    Login: {
      username: string;

      password: string;
    };
    Error: {
      code: string;
    };
    TaskType: {
      code: components["schemas"]["Type"];
      name: string;
    };
    Worker: {
      id: number;
      name: string;
    };
    Create: {
      name: string;
      description: string;
      type: components["schemas"]["Type"];
    };
    Version: {
      version: number;
    };
    Edit: {
      version: number;
      description: string;
      type: components["schemas"]["Type"];
    };
    SelectWorker: {
      version: number;

      workerId: number;
    };
    Result: {
      id: string;

      status: "success" | "fail";

      processingTime: number;

      data: string;
    };
    History: {
      id: number;

      at: string;

      actorId: number | null;
      action: string;

      details: string;
    };
    Task: {
      id: number;
      name: string;
      description: string;
      type: components["schemas"]["Type"];
      typeName: string;
      state: components["schemas"]["State"];

      version: number;

      authorId: number;

      operatorId: number | null;

      workerId: number | null;

      operationId: string | null;
      exchange: components["schemas"]["Exchange"];
      problem: components["schemas"]["Problem"];
      externalId: string | null;
      result: components["schemas"]["Result"] | null;

      actions: (
        | "edit"
        | "worker"
        | "send"
        | "retry"
        | "return"
        | "accept"
        | "reconcile"
      )[];
    };
    TaskPage: {
      items: components["schemas"]["Task"][];

      total: number;
      page: number;
      size: number;
    };
  };
  responses: {
    Error400: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error401: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error403: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error404: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error409: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error422: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };

    Error500: {
      headers: {
        "X-CSRF-TOKEN"?: string;
        [name: string]: unknown;
      };
      content: {
        "application/json": components["schemas"]["Error"];
      };
    };
  };
  parameters: {
    CsrfToken: string;

    IdempotencyKey: string;
  };
  requestBodies: never;
  headers: never;
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  login: {
    parameters: {
      query?: never;
      header: {
        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Login"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Actor"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  logout: {
    parameters: {
      query?: never;
      header: {
        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      204: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content?: never;
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  me: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Actor"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  workers: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Worker"][];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  taskTypes: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["TaskType"][];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  tasks: {
    parameters: {
      query?: {
        type?: components["schemas"]["Type"];
        page?: number;
        size?: number;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["TaskPage"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  createTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Create"];
      };
    };
    responses: {
      201: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  task: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  editTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Edit"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  selectWorker: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["SelectWorker"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  history: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["History"][];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  downloadFile: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "Content-Disposition"?: string;
          "X-Content-Type-Options"?: string;

          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/octet-stream": string;
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  problems: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"][];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  sendTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Version"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  retryTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Version"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  returnTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Version"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  acceptTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Version"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
  reconcileTask: {
    parameters: {
      query?: never;
      header: {
        "Idempotency-Key": components["parameters"]["IdempotencyKey"];

        "X-CSRF-TOKEN": components["parameters"]["CsrfToken"];
      };
      path: {
        id: number;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        "application/json": components["schemas"]["Version"];
      };
    };
    responses: {
      200: {
        headers: {
          "X-CSRF-TOKEN"?: string;
          [name: string]: unknown;
        };
        content: {
          "application/json": components["schemas"]["Task"];
        };
      };
      400: components["responses"]["Error400"];
      401: components["responses"]["Error401"];
      403: components["responses"]["Error403"];
      404: components["responses"]["Error404"];
      409: components["responses"]["Error409"];
      422: components["responses"]["Error422"];
      500: components["responses"]["Error500"];
    };
  };
}
