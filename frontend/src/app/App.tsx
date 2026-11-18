import { useEffect, useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  BrowserRouter,
  Link,
  NavLink,
  Navigate,
  Route,
  Routes,
} from "react-router-dom";
import { api, ApiError, type Actor } from "../shared/api/client";
import { ErrorNotice } from "../shared/Feedback";
import { Login, SessionContext } from "../features/session/Session";
import { Registry } from "../pages/Registry";
import { CreateTask } from "../pages/CreateTask";
import { TaskCard } from "../pages/TaskCard";

export function App() {
  const [cache] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: true },
          mutations: { retry: false },
        },
      }),
  );
  const [actor, setActor] = useState<Actor | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    api.onUnauthorized = () => {
      setActor(null);
      cache.clear();
      setError(new ApiError(401, "Unauthorized"));
    };
    api
      .me(controller.signal)
      .then(setActor)
      .catch((failure: unknown) => {
        if (
          controller.signal.aborted ||
          (failure instanceof ApiError && failure.status === 401)
        )
          return;
        setError(failure);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => {
      controller.abort();
      api.onUnauthorized = undefined;
    };
  }, [cache]);
  async function logout() {
    setLoggingOut(true);
    setError(null);
    try {
      await api.logout();
      setActor(null);
      cache.clear();
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        setActor(null);
        cache.clear();
      } else setError(failure);
    } finally {
      setLoggingOut(false);
    }
  }
  return (
    <QueryClientProvider client={cache}>
      <BrowserRouter>
        <a className="skip-link" href="#content">
          К содержимому
        </a>
        <header>
          <Link to="/tasks" className="brand">
            Обмен<span> / задания</span>
          </Link>
          {actor && (
            <div className="account">
              <span>
                {actor.name} ·{" "}
                {actor.role === "INITIATOR" ? "Инициатор" : "Оператор"} · Служба
                № {actor.serviceId}
              </span>
              <button
                className="secondary"
                disabled={loggingOut}
                onClick={() => void logout()}
              >
                {loggingOut ? "Выход…" : "Выйти"}
              </button>
            </div>
          )}
        </header>
        {actor && (
          <nav className="main-nav" aria-label="Основная навигация">
            <NavLink to="/tasks" end>
              Реестр
            </NavLink>
            <NavLink to="/problems">Проблемы</NavLink>
          </nav>
        )}
        <main id="content">
          <ErrorNotice error={error} />
          {loading ? (
            <p role="status">Проверка сессии…</p>
          ) : actor ? (
            <SessionContext value={actor}>
              <Routes>
                <Route path="/tasks" element={<Registry />} />
                <Route path="/tasks/new" element={<CreateTask />} />
                <Route path="/tasks/:id" element={<TaskCard />} />
                <Route path="/problems" element={<Registry onlyProblems />} />
                <Route path="*" element={<Navigate to="/tasks" replace />} />
              </Routes>
            </SessionContext>
          ) : (
            <Login
              onLogin={(user) => {
                cache.clear();
                setError(null);
                setActor(user);
              }}
            />
          )}
        </main>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
