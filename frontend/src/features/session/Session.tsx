import { createContext, useContext, useState, type FormEvent } from "react";
import type { Actor } from "../../shared/api/client";
import { api } from "../../shared/api/client";
import { ErrorNotice } from "../../shared/Feedback";

export const SessionContext = createContext<Actor | null>(null);
export function useActor() {
  const actor = useContext(SessionContext);
  if (!actor) throw new Error("Требуется вход");
  return actor;
}

export function Login({ onLogin }: { onLogin: (actor: Actor) => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  async function submit(event: FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      try {
        await api.me();
      } catch (failure) {
        if (
          !(failure instanceof Error) ||
          !("status" in failure) ||
          failure.status !== 401
        )
          throw failure;
      }
      onLogin(await api.login({ username, password }));
    } catch (failure) {
      setError(failure);
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="panel login">
      <p className="eyebrow">Система обмена</p>
      <h1>Вход в службу</h1>
      <p>Используйте демонстрационную учётную запись вашей службы.</p>
      <form onSubmit={submit}>
        <fieldset disabled={pending}>
          <label htmlFor="username">Логин</label>
          <input
            id="username"
            autoComplete="username"
            required
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
          <label htmlFor="password">Пароль</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <button>{pending ? "Вход…" : "Войти"}</button>
        </fieldset>
        <ErrorNotice error={error} />
      </form>
    </section>
  );
}
