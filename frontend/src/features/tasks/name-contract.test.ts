import { expect, it } from "vitest";
import { validateTask } from "./model";

it.each([
  ..."ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя0123456789-_.",
])("F-T014 / F-R014 / F-P04: разрешённый символ %s", (name) => {
  const validation = validateTask(name, "");
  expect(validation.errors.name).toBeUndefined();
  expect(validation.name).toBe(name);
});
