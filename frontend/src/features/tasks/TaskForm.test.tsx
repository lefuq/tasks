import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { taskTypes } from "../../test/fixtures";
import { TaskForm } from "./TaskForm";

it("F-T014: форма передаёт обрезанное имя и допускает пустое описание", async () => {
  const onSave = vi.fn();
  const client = new QueryClient();
  client.setQueryData(["task-types"], taskTypes);
  render(
    <QueryClientProvider client={client}>
      <TaskForm pending={false} error={null} onSave={onSave} />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Имя задания"), "  Ёж-1_А.txt  ");
  await user.selectOptions(screen.getByLabelText("Тип задания"), "research");
  await user.click(screen.getByRole("button", { name: "Создать задание" }));
  expect(onSave).toHaveBeenCalledWith({
    name: "Ёж-1_А.txt",
    description: "",
    type: "research",
  });
});

it("F-T015: ошибка поля доступна по подписи, запрос не отправляется", async () => {
  const onSave = vi.fn();
  const client = new QueryClient();
  client.setQueryData(["task-types"], taskTypes);
  render(
    <QueryClientProvider client={client}>
      <TaskForm pending={false} error={null} onSave={onSave} />
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Имя задания"), "A B");
  await user.click(screen.getByRole("button", { name: "Создать задание" }));
  expect(screen.getByLabelText("Имя задания")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  expect(screen.getByRole("alert")).toHaveTextContent(
    "Пробелы внутри запрещены",
  );
  expect(onSave).not.toHaveBeenCalled();
});
