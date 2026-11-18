import { describe, expect, it } from "vitest";
import { validateTask } from "./model";

describe("F-T002, F-T014, F-T015: поля задания", () => {
  it.each([
    ["", false],
    ["a", true],
    ["a".repeat(100), true],
    ["a".repeat(101), false],
    ["   ", false],
    ["A B", false],
    ["A[B", false],
    ["😀", false],
    ["  Ёж-1_А.txt  ", true],
  ])("проверяет имя %s", (name, valid) => {
    expect(!validateTask(name, "").errors.name).toBe(valid);
  });
  it("обрезает Unicode-пробелы и сохраняет разрешённые символы", () => {
    expect(validateTask("\u2003Ёж-1_А.txt\u0085", "").name).toBe("Ёж-1_А.txt");
  });
  it.each([
    [0, true],
    [1000, true],
    [1001, false],
  ])("проверяет описание длины %i", (length, valid) => {
    expect(!validateTask("A", "я".repeat(length)).errors.description).toBe(
      valid,
    );
  });
});
