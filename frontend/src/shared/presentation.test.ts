import { describe, expect, it, vi } from "vitest";
import { historyDetails, localTime, saveFile } from "./presentation";

describe("F-T009–F-T011: представление времени и безопасного файла", () => {
  it("переводит UTC в пояс браузера с названием пояса", () => {
    expect(localTime("2026-11-14T00:00:00Z", "Asia/Krasnoyarsk")).toBe(
      "14.11.2026, 07:00:00 (Asia/Krasnoyarsk)",
    );
  });
  it.each(["<script>alert(1)</script>", ""])(
    "скачивает .md без HTML, включая пустой файл",
    (content) => {
      const create = vi.fn(() => "blob:local-test");
      const revoke = vi.fn();
      vi.stubGlobal(
        "URL",
        class extends URL {
          static createObjectURL = create;
          static revokeObjectURL = revoke;
        },
      );
      const click = vi
        .spyOn(HTMLAnchorElement.prototype, "click")
        .mockImplementation(function (this: HTMLAnchorElement) {
          expect(this.download).toBe("task-10.md");
          expect(this.href).toBe("blob:local-test");
        });
      vi.useFakeTimers();
      saveFile(new Blob([content]), 10);
      expect(click).toHaveBeenCalledOnce();
      expect(create.mock.calls[0]?.length).toBe(1);
      expect(document.querySelector("script")).toBeNull();
      expect(document.querySelector("a")).toBeNull();
      vi.runAllTimers();
      expect(revoke).toHaveBeenCalledWith("blob:local-test");
      vi.useRealTimers();
    },
  );
  it("раскрывает вложенный JSON сверки и сохраняет неизвестные сообщения", () => {
    expect(
      historyDetails('["operation", "{\\"status\\":\\"fail\\"}"]'),
    ).toContain('"status": "fail"');
    expect(historyDetails("invalid-json")).toBe("invalid-json");
  });
});

it("показывает назначение оператора для старых и новых записей", () => {
  expect(historyDetails("12", "assigned")).toBe("Назначен оператор #12");
  expect(
    historyDetails(JSON.stringify("Назначен оператор #12"), "assigned"),
  ).toBe("Назначен оператор #12");
});

it("показывает только изменённые поля, сохраняет строковые значения и раскрывает вложенные поля", () => {
  const before = {
    id: 10,
    description: "123",
    state: "NEW",
    nested: { a: 1, b: 2 },
  };
  const after = {
    ...before,
    description: "456",
    state: "WAITING_TO_SEND",
    nested: { a: 1, b: 3 },
  };
  const details = historyDetails(
    JSON.stringify([JSON.stringify(before), JSON.stringify(after)]),
  );
  expect(details).toBe(
    '− Описание: "123"\n+ Описание: "456"\n− Состояние: Новое\n+ Состояние: Ожидает отправки\n− nested.b: 2\n+ nested.b: 3',
  );
  expect(details).not.toContain("Номер задания");
});

it("использует названия типов из справочника в диффе", () => {
  expect(
    historyDetails('[{"type":"process"},{"type":"research"}]', "edit", {
      process: "Обработка",
      research: "Исследование",
    }),
  ).toBe("− Тип: Обработка\n+ Тип: Исследование");
});
