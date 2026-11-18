import { useState, type FormEvent } from "react";
import type { Create, Task } from "../../shared/api/client";
import { ErrorNotice } from "../../shared/Feedback";
import { validateTask } from "./model";
import { useTaskTypes } from "./useTaskTypes";

export function TaskForm({
  task,
  pending,
  disabled = false,
  error,
  onSave,
}: {
  task?: Task;
  pending: boolean;
  disabled?: boolean;
  error: unknown;
  onSave: (body: Create) => void;
}) {
  const types = useTaskTypes();
  const [name, setName] = useState(task?.name ?? "");
  const [description, setDescription] = useState(task?.description ?? "");
  const [type, setType] = useState<Create["type"]>(task?.type ?? "process");
  const [errors, setErrors] = useState<{ name?: string; description?: string }>(
    {},
  );
  function submit(event: FormEvent) {
    event.preventDefault();
    const validation = validateTask(name, description);
    setErrors(validation.errors);
    if (Object.keys(validation.errors).length === 0)
      onSave({ name: validation.name, description, type });
  }
  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label={task ? "Редактирование задания" : "Создание задания"}
    >
      <fieldset disabled={pending || disabled}>
        <label htmlFor="task-name">Имя задания</label>
        <input
          id="task-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          readOnly={!!task}
          aria-invalid={!!errors.name}
          aria-describedby={errors.name ? "name-error" : "name-help"}
        />
        <small id="name-help">
          1–100 символов. После создания имя не меняется.
        </small>
        {errors.name && (
          <p role="alert" id="name-error" className="error">
            {errors.name}
          </p>
        )}
        <label htmlFor="task-description">Описание</label>
        <textarea
          id="task-description"
          rows={5}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          aria-invalid={!!errors.description}
          aria-describedby="description-help"
        />
        <small id="description-help">
          {errors.description ?? "До 1000 символов; может быть пустым."}
        </small>
        {errors.description && (
          <p role="alert" className="error">
            {errors.description}
          </p>
        )}
        <label htmlFor="task-type">Тип задания</label>
        <select
          id="task-type"
          value={type}
          onChange={(event) => setType(event.target.value as Create["type"])}
        >
          {types.data?.map((item) => (
            <option key={item.code} value={item.code}>
              {item.name}
            </option>
          ))}
        </select>
        <ErrorNotice error={types.error} />
        <button type="submit" disabled={!types.data}>
          {pending ? "Сохранение…" : task ? "Сохранить" : "Создать задание"}
        </button>
      </fieldset>
      <ErrorNotice error={error} />
    </form>
  );
}
