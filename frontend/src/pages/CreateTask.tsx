import { useNavigate } from "react-router-dom";
import { TaskForm } from "../features/tasks/TaskForm";
import {
  createTask,
  isUncertain,
  useTaskMutation,
} from "../features/tasks/useTaskMutation";
import { useActor } from "../features/session/Session";

export function CreateTask() {
  const actor = useActor();
  const navigate = useNavigate();
  const mutation = useTaskMutation("create", createTask);
  if (actor.role !== "INITIATOR")
    return <p role="alert">Создание доступно только инициатору.</p>;
  return (
    <section className="panel narrow">
      <h1>Новое задание</h1>
      <TaskForm
        pending={mutation.isPending}
        disabled={isUncertain(mutation.error)}
        error={mutation.error}
        onSave={(body) =>
          mutation.mutate(body, {
            onSuccess: (task) => void navigate(`/tasks/${task.id}`),
          })
        }
      />
      {isUncertain(mutation.error) && mutation.variables && (
        <button
          disabled={mutation.isPending}
          onClick={() =>
            mutation.mutate(mutation.variables!, {
              onSuccess: (task) => void navigate(`/tasks/${task.id}`),
            })
          }
        >
          Повторить неподтверждённое действие
        </button>
      )}
    </section>
  );
}
