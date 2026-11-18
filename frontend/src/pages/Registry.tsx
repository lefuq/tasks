import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { api, type Task, type TaskPage } from "../shared/api/client";
import { ErrorNotice } from "../shared/Feedback";
import { states, problems } from "../shared/presentation";
import { useActor } from "../features/session/Session";
import { useTaskTypes } from "../features/tasks/useTaskTypes";

export function TaskList({ tasks }: { tasks: Task[] }) {
  if (!tasks.length) return <p className="empty">Заданий пока нет.</p>;
  return (
    <ul className="task-list">
      {tasks.map((task) => (
        <li key={task.id}>
          <div>
            <Link to={`/tasks/${task.id}`} className="task-name">
              {task.name}
            </Link>
            <p className="muted">
              № {task.id} · {task.typeName}
            </p>
          </div>
          <div>
            <span className="badge">{states[task.state]}</span>
            {task.problem && <p className="issue">{problems[task.problem]}</p>}
          </div>
        </li>
      ))}
    </ul>
  );
}

export function Registry({ onlyProblems = false }: { onlyProblems?: boolean }) {
  const actor = useActor();
  const types = useTaskTypes();
  const [params, setParams] = useSearchParams();
  const type = params.get("type") ?? "";
  const pageValue = Number(params.get("page") ?? 0);
  const page =
    Number.isSafeInteger(pageValue) && pageValue >= 0 ? pageValue : 0;
  const list = useQuery<Task[] | TaskPage>({
    queryKey: onlyProblems ? ["problems"] : ["tasks", type, page],
    queryFn: ({ signal }) =>
      onlyProblems ? api.problems(signal) : api.tasks(type, page, signal),
    refetchInterval: 10000,
  });
  const items = Array.isArray(list.data) ? list.data : list.data?.items;
  const pagination = list.data && !Array.isArray(list.data) ? list.data : null;
  return (
    <section className="panel">
      <div className="section-head">
        <div>
          <p className="eyebrow">Служба № {actor.serviceId}</p>
          <h1>{onlyProblems ? "Проблемы обмена" : "Задания"}</h1>
        </div>
        {!onlyProblems && actor.role === "INITIATOR" && (
          <Link className="button" to="/tasks/new">
            Создать задание
          </Link>
        )}
      </div>
      {!onlyProblems && (
        <div className="filter">
          <label htmlFor="filter-type">Тип задания</label>
          <select
            id="filter-type"
            value={type}
            onChange={(event) =>
              setParams({ type: event.target.value, page: "0" })
            }
          >
            <option value="">Все типы</option>
            {types.data?.map((item) => (
              <option key={item.code} value={item.code}>
                {item.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {list.isPending && <p role="status">Загрузка заданий…</p>}
      <ErrorNotice error={list.error || types.error} />
      {list.isError && (
        <button onClick={() => void list.refetch()}>Повторить загрузку</button>
      )}
      {items && <TaskList tasks={items} />}
      {pagination && (
        <nav aria-label="Страницы реестра" className="pagination">
          <button
            disabled={pagination.page === 0 || list.isFetching}
            onClick={() => setParams({ type, page: String(page - 1) })}
          >
            Назад
          </button>
          <span>
            Страница {pagination.page + 1} · Всего {pagination.total}
          </span>
          <button
            disabled={
              (pagination.page + 1) * pagination.size >= pagination.total ||
              list.isFetching
            }
            onClick={() => setParams({ type, page: String(page + 1) })}
          >
            Далее
          </button>
        </nav>
      )}
    </section>
  );
}
