import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import {
  api,
  ApiError,
  type Action,
  type Create,
  type Task,
  type Worker,
  type History,
} from "../shared/api/client";
import { ErrorNotice } from "../shared/Feedback";
import {
  actionNames,
  exchanges,
  historyDetails,
  historyNames,
  localTime,
  problems,
  saveFile,
  states,
} from "../shared/presentation";
import { useTaskTypes } from "../features/tasks/useTaskTypes";
import { TaskForm } from "../features/tasks/TaskForm";
import {
  isUncertain,
  useTaskMutation,
} from "../features/tasks/useTaskMutation";

function HistoryList({ history }: { history: History[] }) {
  const types = useTaskTypes();
  const typeNames = Object.fromEntries(
    types.data?.map((item) => [item.code, item.name]) ?? [],
  );
  if (!history.length) return <p>История пуста.</p>;
  return (
    <ol className="history">
      {history.map((item) => (
        <li key={item.id}>
          <strong>{historyNames[item.action] ?? item.action}</strong>
          <p>
            <time dateTime={item.at}>{localTime(item.at)}</time> ·{" "}
            {item.actorId === null
              ? "Система"
              : `Пользователь № ${item.actorId}`}
          </p>
          <details>
            <summary>Подробности события</summary>
            <pre>{historyDetails(item.details, item.action, typeNames)}</pre>
          </details>
        </li>
      ))}
    </ol>
  );
}

type Command = {
  action: Action;
  body: {
    version: number;
    workerId?: number;
    description?: string;
    type?: Create["type"];
  };
};

export function TaskWorkspace({
  task,
  workers,
  history,
  onRefresh,
}: {
  task: Task;
  workers: Worker[];
  history: History[];
  onRefresh: () => void;
}) {
  const [draftVersion, setDraftVersion] = useState(task.version);
  const [workerId, setWorkerId] = useState(task.workerId?.toString() ?? "");
  const [fileError, setFileError] = useState<unknown>(null);
  const [downloading, setDownloading] = useState(false);
  const mutation = useTaskMutation<Command>(`task-${task.id}`, (command, key) =>
    api.command(task.id, command.action, command.body, key),
  );
  const conflict =
    mutation.error instanceof ApiError && mutation.error.status === 409;
  const uncertain = isUncertain(mutation.error);
  const busy = mutation.isPending || conflict || uncertain;
  const worker = workers.find((item) => item.id === task.workerId);
  function perform(request: Command) {
    mutation.mutate(request, {
      onSuccess: (updated) => {
        if (request.action === "edit") setDraftVersion(updated.version);
      },
    });
  }
  function command(action: Action) {
    perform({ action, body: { version: task.version } });
  }
  async function download() {
    setDownloading(true);
    setFileError(null);
    try {
      saveFile(await api.file(task.id), task.id);
    } catch (error) {
      setFileError(error);
    } finally {
      setDownloading(false);
    }
  }
  return (
    <>
      <section className="panel">
        <div className="section-head">
          <div>
            <p className="eyebrow">Задание № {task.id}</p>
            <h1>{task.name}</h1>
          </div>
          <span className="badge">{states[task.state]}</span>
        </div>
        <dl className="facts">
          <div>
            <dt>Состояние</dt>
            <dd>{states[task.state]}</dd>
          </div>
          <div>
            <dt>Обмен</dt>
            <dd>{task.exchange ? exchanges[task.exchange] : "Не начат"}</dd>
          </div>
          <div>
            <dt>Оператор</dt>
            <dd>
              {task.operatorId === null
                ? "Не назначен"
                : `№ ${task.operatorId}`}
            </dd>
          </div>
          <div>
            <dt>Исполнитель</dt>
            <dd>
              {worker?.name ??
                (task.workerId === null ? "Не выбран" : `№ ${task.workerId}`)}
            </dd>
          </div>
          <div>
            <dt>Тип</dt>
            <dd>{task.typeName}</dd>
          </div>
          <div>
            <dt>Автор</dt>
            <dd>№ {task.authorId}</dd>
          </div>
          <div>
            <dt>Операция</dt>
            <dd>{task.operationId ?? "Не создана"}</dd>
          </div>
          <div>
            <dt>Внешний ID</dt>
            <dd>{task.externalId ?? "Не получен"}</dd>
          </div>
        </dl>
        {task.problem && (
          <div className="notice">
            <h2>{problems[task.problem]}</h2>
            {task.problem === "LOST_RESULT" ? (
              <p>
                Внешний результат отсутствует. Выберите доступного исполнителя и
                отправьте задание новой операцией, если сервер разрешает
                отправку.
              </p>
            ) : (
              <p>
                Проблема остаётся открытой до подтверждённого решения. Проверьте
                историю и материалы сверки.
              </p>
            )}
          </div>
        )}
        {task.exchange === "UNKNOWN" && (
          <p className="notice">
            Ответ потерян: задание могло быть принято исполнителем. Повтор и
            сверка используют прежнюю операцию и не запускают второе исполнение.
          </p>
        )}
        {task.result ? (
          <>
            <p>
              Результат: <strong>{task.result.status}</strong>
            </p>
            <p>Длительность исполнения: {task.result.processingTime} секунд</p>
            {task.problem === "MISMATCH" && (
              <p>
                Показан первый сохранённый результат. Подтверждённый сверкой
                ответ доступен в истории; принятие фиксирует подтверждённый
                результат.
              </p>
            )}
            <button onClick={() => void download()} disabled={downloading}>
              {downloading ? "Скачивание…" : "Скачать результат .md"}
            </button>
            <ErrorNotice error={fileError} />
          </>
        ) : (
          <>
            <p>Результат: готового результата пока нет.</p>
            <p>Длительность исполнения: —</p>
          </>
        )}
        <div className="commands" aria-label="Действия задания">
          {task.actions
            .filter((action) => action !== "edit" && action !== "worker")
            .map((action) => (
              <button
                key={action}
                disabled={busy}
                onClick={() => command(action)}
              >
                {actionNames[action]}
              </button>
            ))}
        </div>
        {mutation.isPending && (
          <p role="status">
            Выполняется запрос. Изменение ещё не подтверждено.
          </p>
        )}
        <ErrorNotice error={mutation.error} />
        {uncertain && mutation.variables && (
          <button
            disabled={mutation.isPending}
            onClick={() => perform(mutation.variables!)}
          >
            Повторить неподтверждённое действие
          </button>
        )}
        <button
          className="secondary"
          disabled={mutation.isPending || uncertain}
          onClick={onRefresh}
        >
          Обновить карточку
        </button>
      </section>
      {task.actions.includes("worker") && (
        <section className="panel">
          <h2>Исполнитель</h2>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              mutation.mutate({
                action: "worker",
                body: { version: task.version, workerId: Number(workerId) },
              });
            }}
          >
            <fieldset disabled={busy}>
              <label htmlFor="worker">Исполнитель</label>
              <select
                id="worker"
                required
                value={workerId}
                onChange={(event) => setWorkerId(event.target.value)}
              >
                <option value="">Выберите исполнителя</option>
                {workers.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <button disabled={!workerId}>Сохранить исполнителя</button>
            </fieldset>
          </form>
          {workers.length === 0 && <p>Нет доступных исполнителей.</p>}
        </section>
      )}
      {task.actions.includes("edit") ? (
        <section className="panel">
          <h2>Редактирование</h2>
          <TaskForm
            task={task}
            pending={mutation.isPending}
            disabled={busy}
            error={null}
            onSave={(body) =>
              mutation.mutate(
                {
                  action: "edit",
                  body: {
                    version: draftVersion,
                    description: body.description,
                    type: body.type,
                  },
                },
                { onSuccess: (updated) => setDraftVersion(updated.version) },
              )
            }
          />
        </section>
      ) : (
        <section className="panel">
          <h2>Описание</h2>
          <p className="description">
            {task.description || "Описание не задано."}
          </p>
        </section>
      )}
      <section className="panel">
        <h2>История обмена и сверки</h2>
        <HistoryList history={history} />
      </section>
    </>
  );
}

export function TaskCard() {
  const { id } = useParams();
  const taskId = Number(id);
  const [revision, setRevision] = useState(0);
  const task = useQuery({
    queryKey: ["task", taskId],
    queryFn: ({ signal }) => api.task(taskId, signal),
    refetchInterval: 10000,
  });
  const workers = useQuery({
    queryKey: ["workers"],
    queryFn: ({ signal }) => api.workers(signal),
  });
  const history = useQuery({
    queryKey: ["history", taskId],
    queryFn: ({ signal }) => api.history(taskId, signal),
    refetchInterval: 10000,
  });
  async function refresh() {
    const response = await task.refetch();
    void history.refetch();
    void workers.refetch();
    if (response.isSuccess) setRevision((value) => value + 1);
  }
  return (
    <>
      <Link to="/tasks">← Реестр заданий</Link>
      {task.isPending && <p role="status">Загрузка карточки…</p>}
      <ErrorNotice error={task.error || workers.error || history.error} />
      {task.isError && (
        <button onClick={() => void refresh()}>Повторить загрузку</button>
      )}
      {task.data && (
        <TaskWorkspace
          key={`${taskId}-${revision}`}
          task={task.data}
          workers={workers.data ?? []}
          history={history.data ?? []}
          onRefresh={() => void refresh()}
        />
      )}
    </>
  );
}
