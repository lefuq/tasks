import { expect, test, type Locator, type Page } from "@playwright/test";
import type { Task } from "../src/shared/api/client";

async function tabTo(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  for (let index = 0; index < 80; index++) {
    if (await target.evaluate((element) => element === document.activeElement))
      return;
    await page.keyboard.press("Tab");
  }
  throw new Error("Элемент недоступен с клавиатуры");
}

async function activate(page: Page, target: Locator) {
  await tabTo(page, target);
  await page.keyboard.press("Enter");
}

async function login(page: Page, username: string) {
  await page.goto("/tasks");
  await tabTo(page, page.getByLabel("Логин"));
  await page.keyboard.type(username);
  await page.keyboard.press("Tab");
  await page.keyboard.type("isolated-demo-only");
  await activate(
    page,
    page.getByRole("button", { name: "Войти", exact: true }),
  );
  await expect(
    page.getByRole("heading", { name: "Задания", exact: true }),
  ).toBeVisible();
}

async function configure(page: Page, fault: string, outcome = "success") {
  const response = await page.request.put(
    `${process.env.TEST_EMULATOR_URL}/admin/workers/worker-1`,
    { data: { fault, outcome } },
  );
  expect(response.ok()).toBeTruthy();
}

async function create(page: Page) {
  await activate(page, page.getByRole("link", { name: "Создать задание" }));
  await tabTo(page, page.getByLabel("Имя задания"));
  await page.keyboard.type(`UI-${crypto.randomUUID()}`);
  const response = page.waitForResponse(
    (item) =>
      item.url().endsWith("/api/tasks") && item.request().method() === "POST",
  );
  await activate(page, page.getByRole("button", { name: "Создать задание" }));
  const task = (await (await response).json()) as Task;
  await expect(
    page.getByRole("heading", { name: task.name, exact: true }),
  ).toBeVisible();
  return task;
}

async function switchUser(page: Page, username: string) {
  await activate(
    page,
    page.getByRole("button", { name: "Выйти", exact: true }),
  );
  await expect(
    page.getByRole("heading", { name: "Вход в службу" }),
  ).toBeVisible();
  await login(page, username);
}

async function selectAndSend(page: Page) {
  await tabTo(page, page.getByLabel("Исполнитель", { exact: true }));
  await expect(page.getByLabel("Исполнитель", { exact: true })).toBeFocused();
  await page.getByLabel("Исполнитель", { exact: true }).selectOption("1");
  const response = page.waitForResponse(
    (item) =>
      item.url().endsWith("/worker") && item.request().method() === "PUT",
  );
  await activate(
    page,
    page.getByRole("button", { name: "Сохранить исполнителя" }),
  );
  expect((await response).ok()).toBeTruthy();
  await expect(
    page.getByRole("button", { name: "Отправить", exact: true }),
  ).toBeEnabled();
  await activate(
    page,
    page.getByRole("button", { name: "Отправить", exact: true }),
  );
}

test("F-T001/F-T012: создание, назначение, отправка, файл, принятие клавиатурой", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().includes("401"))
      errors.push(message.text());
  });
  await configure(page, "normal", "fail");
  await login(page, "initiator1");
  const task = await create(page);
  expect(task.operatorId).not.toBeNull();
  await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
  await page.goto(`/tasks/${task.id}`);
  await selectAndSend(page);
  await expect(
    page.getByRole("button", { name: "Принять результат" }),
  ).toBeVisible();
  await expect(page.getByText("fail", { exact: true })).toBeVisible();
  const download = page.waitForEvent("download");
  await activate(
    page,
    page.getByRole("button", { name: "Скачать результат .md" }),
  );
  expect((await download).suggestedFilename()).toBe(`task-${task.id}.md`);
  await page.screenshot({
    path: testInfo.outputPath("result.png"),
    fullPage: true,
  });
  await activate(page, page.getByRole("button", { name: "Принять результат" }));
  await expect(
    page.locator("dd").filter({ hasText: /^Завершено$/ }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Сохранить", exact: true }),
  ).toHaveCount(0);
  const current = (await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json()) as Task;
  const inspection = await (
    await page.request.get(
      `${process.env.TEST_EMULATOR_URL}/admin/operations/${current.externalId}`,
    )
  ).json();
  expect(inspection.execution_count).toBe(1);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  expect(errors).toEqual([]);
  await switchUser(page, "initiator1");
});

test("F-T005: технически невалидный результат, возврат и сохранение без изменения полей", async ({
  page,
}) => {
  await configure(page, "invalid_json");
  await login(page, "initiator1");
  const task = await create(page);
  const username = task.operatorId === 3 ? "operator1" : "operator2";
  await switchUser(page, username);
  await page.goto(`/tasks/${task.id}`);
  await selectAndSend(page);
  await expect(
    page.getByRole("heading", { name: "Технически невалидный результат" }),
  ).toBeVisible();
  const original = (await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json()) as Task;
  await activate(
    page,
    page.getByRole("button", { name: "Вернуть инициатору" }),
  );
  await expect(
    page.locator("dd").filter({ hasText: /^Возвращено инициатору$/ }),
  ).toBeVisible();
  await switchUser(page, "initiator1");
  await page.goto(`/tasks/${task.id}`);
  await activate(
    page,
    page.getByRole("button", { name: "Сохранить", exact: true }),
  );
  await expect(
    page.locator("dd").filter({ hasText: /^Ожидает отправки$/ }),
  ).toBeVisible();
  await configure(page, "normal");
  await switchUser(page, username);
  await page.goto(`/tasks/${task.id}`);
  await activate(
    page,
    page.getByRole("button", { name: "Отправить", exact: true }),
  );
  await expect(
    page.getByRole("button", { name: "Принять результат" }),
  ).toBeVisible();
  const updated = (await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json()) as Task;
  expect(updated.operationId).not.toBe(original.operationId);
  expect(updated.operatorId).toBe(original.operatorId);
});

test("F-T013: утеря результата, смена исполнителя и новая операция", async ({
  page,
}) => {
  await configure(page, "lost_result");
  await login(page, "initiator1");
  const task = await create(page);
  await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
  await page.goto(`/tasks/${task.id}`);
  await selectAndSend(page);
  await expect(
    page.getByRole("heading", { name: "Результат утерян" }),
  ).toBeVisible();
  const previous = (await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json()) as Task;
  const configureSecond = await page.request.put(
    `${process.env.TEST_EMULATOR_URL}/admin/workers/worker-2`,
    { data: { fault: "normal", outcome: "success" } },
  );
  expect(configureSecond.ok()).toBeTruthy();
  await tabTo(page, page.getByLabel("Исполнитель", { exact: true }));
  await expect(page.getByLabel("Исполнитель", { exact: true })).toBeFocused();
  await page.getByLabel("Исполнитель", { exact: true }).selectOption("2");
  await activate(
    page,
    page.getByRole("button", { name: "Сохранить исполнителя" }),
  );
  await expect(
    page.getByRole("button", { name: "Отправить", exact: true }),
  ).toBeEnabled();
  await activate(
    page,
    page.getByRole("button", { name: "Отправить", exact: true }),
  );
  await expect(
    page.getByRole("button", { name: "Принять результат" }),
  ).toBeVisible();
  const current = (await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json()) as Task;
  expect(current.operationId).not.toBe(previous.operationId);
  expect(current.workerId).toBe(2);
});

test("F-T001: сессия сохраняется после reload, выход закрывает прямой маршрут", async ({
  page,
}) => {
  await login(page, "initiator1");
  const response = await page.request.get("/api/me");
  expect(response.ok()).toBeTruthy();
  const actor = await response.json();
  await expect(page.locator(".account")).toContainText(actor.name);
  await expect(page.locator(".account")).toContainText(
    `Служба № ${actor.serviceId}`,
  );
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Задания", exact: true }),
  ).toBeVisible();
  await activate(
    page,
    page.getByRole("button", { name: "Выйти", exact: true }),
  );
  await page.goto("/tasks");
  await expect(
    page.getByRole("heading", { name: "Вход в службу" }),
  ).toBeVisible();
  expect((await page.request.get("/api/tasks")).status()).toBe(401);
  await login(page, "operator1");
  await expect(page.getByRole("link", { name: "Создать задание" })).toHaveCount(
    0,
  );
  await page.goto("/tasks/new");
  await expect(
    page.getByRole("button", { name: "Создать задание" }),
  ).toHaveCount(0);
});

test("F-T012: промежуточные ширины реестра и формы", async ({
  page,
}, testInfo) => {
  await login(page, "initiator1");
  for (const width of [480, 768, 1024, 1280]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/tasks");
    await expect(
      page.getByRole("heading", { name: "Задания", exact: true }),
    ).toBeVisible();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    await activate(page, page.getByRole("link", { name: "Создать задание" }));
    await expect(page.getByLabel("Имя задания")).toBeVisible();
    await tabTo(page, page.getByRole("button", { name: "Создать задание" }));
    await expect(
      page.getByRole("button", { name: "Создать задание" }),
    ).toBeFocused();
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBeTruthy();
    await page.screenshot({
      path: testInfo.outputPath(`form-${width}.png`),
      fullPage: true,
    });
  }
});

test("F-T003: реальный фильтр и пагинация соответствуют API", async ({
  page,
}) => {
  await login(page, "initiator1");
  for (const type of [
    ...Array<string>(21).fill("research"),
    "process",
    "collect",
  ]) {
    await page.goto("/tasks/new");
    await page.getByLabel("Имя задания").fill(`Filter-${crypto.randomUUID()}`);
    await page.getByLabel("Тип задания").selectOption(type);
    const response = page.waitForResponse(
      (item) =>
        item.url().endsWith("/api/tasks") && item.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Создать задание" }).click();
    expect((await response).ok()).toBeTruthy();
  }
  await page.goto("/tasks");
  await page.getByLabel("Тип задания").selectOption("research");
  for (const index of [0, 1]) {
    const response = await page.request.get(
      `/api/tasks?type=research&page=${index}`,
    );
    expect(response.ok()).toBeTruthy();
    const registry = await response.json();
    await expect(page.locator(".task-list li")).toHaveCount(
      registry.items.length,
    );
    await expect(
      page.getByText(`Страница ${index + 1} · Всего ${registry.total}`, {
        exact: true,
      }),
    ).toBeVisible();
    for (const task of registry.items) {
      expect(task.type).toBe("research");
      await expect(
        page.getByRole("link", { name: task.name, exact: true }),
      ).toBeVisible();
    }
    if (index === 0)
      await page.getByRole("button", { name: "Далее", exact: true }).click();
  }
  await page.getByLabel("Тип задания").selectOption("collect");
  await expect(page).toHaveURL(/page=0/);
  await expect(
    page.getByRole("button", { name: "Назад", exact: true }),
  ).toBeDisabled();
});

test("F-T016: явное принятие противоречащего ответа тестового прокси", async ({
  page,
}, testInfo) => {
  await configure(page, "normal", "success");
  await login(page, "initiator1");
  const task = await create(page);
  await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
  await page.goto(`/tasks/${task.id}`);
  await selectAndSend(page);
  await expect(page.getByText("success", { exact: true })).toBeVisible();
  const original = await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json();
  const control = `${process.env.TEST_PROXY_URL}/__test/contradiction`;
  expect(
    (
      await page.request.put(control, { data: { id: original.externalId } })
    ).ok(),
  ).toBeTruthy();
  try {
    await activate(
      page,
      page.getByRole("button", { name: "Запустить сверку" }),
    );
    await expect(
      page.getByRole("heading", { name: "Расхождение результатов" }),
    ).toBeVisible();
    await expect(page.getByText("success", { exact: true })).toBeVisible();
    const comparison = page
      .locator(".history li")
      .filter({ hasText: '"status": "success"' })
      .filter({ hasText: '"status": "fail"' })
      .first();
    await expect(comparison).toBeVisible();
    await comparison.getByText("Подробности события").click();
    await expect(comparison.locator("pre")).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath("mismatch-history.png"),
      fullPage: true,
    });
    await activate(
      page,
      page.getByRole("button", { name: "Запустить сверку" }),
    );
    await expect(
      page.getByRole("button", { name: "Принять результат" }),
    ).toBeEnabled();
    await activate(
      page,
      page.getByRole("button", { name: "Принять результат" }),
    );
    await expect(
      page.locator("dd").filter({ hasText: /^Завершено$/ }),
    ).toBeVisible();
    await expect(page.getByText("fail", { exact: true })).toBeVisible();
    await expect(
      page.locator(".history").getByText("Результат принят", { exact: true }),
    ).toBeVisible();
    await page.screenshot({
      path: testInfo.outputPath("accepted-fail.png"),
      fullPage: true,
    });
    const current = await (
      await page.request.get(`/api/tasks/${task.id}`)
    ).json();
    expect(current.operationId).toBe(original.operationId);
    expect(current.externalId).toBe(original.externalId);
    const history = await (
      await page.request.get(`/api/tasks/${task.id}/history`)
    ).json();
    expect(JSON.stringify(history)).toContain("success");
    expect(JSON.stringify(history)).toContain("fail");
    expect(JSON.stringify(history)).toContain("accept");
    const inspection = await (
      await page.request.get(
        `${process.env.TEST_EMULATOR_URL}/admin/operations/${current.externalId}`,
      )
    ).json();
    expect(inspection.execution_count).toBe(1);
    expect(inspection.result.status).toBe("success");
    const observations = await (await page.request.get(control)).json();
    expect(observations.observed_original.id).toBe(current.externalId);
    expect(observations.delivered.id).toBe(current.externalId);
    expect(observations.observed_original.status).toBe("success");
    expect(observations.delivered.status).toBe("fail");
    expect(observations.delivered.processing_time).toBe(
      observations.observed_original.processing_time,
    );
  } finally {
    expect((await page.request.delete(control)).ok()).toBeTruthy();
  }
});

for (const fault of ["invalid_json", "lose_response", "unavailable"]) {
  test(`F-T013: восстановление ${fault} прежней операцией`, async ({
    page,
  }) => {
    await configure(page, fault);
    await login(page, "initiator1");
    const task = await create(page);
    await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
    await page.goto(`/tasks/${task.id}`);
    await selectAndSend(page);
    if (fault === "invalid_json") {
      await expect(
        page.getByRole("heading", { name: "Технически невалидный результат" }),
      ).toBeVisible();
    } else if (fault === "lose_response") {
      await expect(
        page.getByText(/Ответ потерян: задание могло быть принято/),
      ).toBeVisible();
    } else {
      await expect(
        page.getByRole("heading", { name: "Временная ошибка связи" }),
      ).toBeVisible();
    }
    const previous = await (
      await page.request.get(`/api/tasks/${task.id}`)
    ).json();
    await configure(page, "normal");
    await activate(
      page,
      page.getByRole("button", {
        name: fault === "lose_response" ? "Запустить сверку" : "Повторить",
        exact: true,
      }),
    );
    await expect(
      page.getByRole("button", { name: "Принять результат" }),
    ).toBeVisible();
    const current = await (
      await page.request.get(`/api/tasks/${task.id}`)
    ).json();
    expect(current.operationId).toBe(previous.operationId);
    const inspection = await (
      await page.request.get(
        `${process.env.TEST_EMULATOR_URL}/admin/operations/${current.externalId}`,
      )
    ).json();
    expect(inspection.execution_count).toBe(1);
  });
}

test("F-T013: после исчерпания автоматических попыток разрешён ручной повтор", async ({
  page,
}) => {
  test.setTimeout(420000);
  await configure(page, "unavailable");
  await login(page, "initiator1");
  const task = await create(page);
  await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
  await page.goto(`/tasks/${task.id}`);
  await selectAndSend(page);
  await expect(
    page.getByRole("heading", { name: "Повторы исчерпаны" }),
  ).toBeVisible({ timeout: 330000 });
  const previous = await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json();
  await expect(page.getByLabel("Исполнитель", { exact: true })).toBeEnabled();
  await configure(page, "normal");
  await activate(
    page,
    page.getByRole("button", { name: "Повторить", exact: true }),
  );
  await expect(
    page.getByRole("button", { name: "Принять результат" }),
  ).toBeVisible();
  const current = await (
    await page.request.get(`/api/tasks/${task.id}`)
  ).json();
  expect(current.operationId).toBe(previous.operationId);
});

for (const exhausted of [false, true]) {
  test(`F-T013: восстановление сменой исполнителя после ${exhausted ? "исчерпания" : "первой ошибки"}`, async ({
    page,
  }) => {
    test.setTimeout(420000);
    await configure(page, "unavailable");
    await login(page, "initiator1");
    const task = await create(page);
    await switchUser(page, task.operatorId === 3 ? "operator1" : "operator2");
    await page.goto(`/tasks/${task.id}`);
    await selectAndSend(page);
    await expect(
      page.getByRole("heading", {
        name: exhausted ? "Повторы исчерпаны" : "Временная ошибка связи",
      }),
    ).toBeVisible({ timeout: 330000 });
    const previous = await (
      await page.request.get(`/api/tasks/${task.id}`)
    ).json();
    expect(
      (
        await page.request.put(
          `${process.env.TEST_EMULATOR_URL}/admin/workers/worker-2`,
          { data: { fault: "normal", outcome: "success" } },
        )
      ).ok(),
    ).toBeTruthy();
    await page.getByLabel("Исполнитель", { exact: true }).selectOption("2");
    await activate(
      page,
      page.getByRole("button", { name: "Сохранить исполнителя" }),
    );
    await expect(
      page.getByRole("button", { name: "Принять результат" }),
    ).toBeVisible();
    const current = await (
      await page.request.get(`/api/tasks/${task.id}`)
    ).json();
    expect(current.operationId).toBe(previous.operationId);
    if (previous.externalId)
      expect(current.externalId).toBe(previous.externalId);
    expect(current.workerId).toBe(2);
    const inspection = await (
      await page.request.get(
        `${process.env.TEST_EMULATOR_URL}/admin/operations/${current.externalId}`,
      )
    ).json();
    expect(inspection.execution_count).toBe(1);
  });
}
