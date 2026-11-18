import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ActionKeys, api, ApiError, type Task } from "../../shared/api/client";

export function useTaskMutation<T>(
  scope: string,
  execute: (variables: T, key: string) => Promise<Task>,
) {
  const cache = useQueryClient();
  const [sessionVersion] = useState(api.sessionVersion);
  const [keys] = useState(() => new ActionKeys());
  return useMutation({
    mutationFn: async (variables: T) => {
      const fingerprint = JSON.stringify([scope, variables]);
      try {
        const task = await execute(variables, keys.get(fingerprint));
        keys.complete(fingerprint);
        return task;
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status >= 400 &&
          error.status < 500
        )
          keys.complete(fingerprint);
        throw error;
      }
    },
    onSuccess: async (task) => {
      if (sessionVersion !== api.sessionVersion) return;
      await cache.cancelQueries({ queryKey: ["task", task.id] });
      cache.setQueryData(["task", task.id], task);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["tasks"] }),
        cache.invalidateQueries({ queryKey: ["problems"] }),
        cache.invalidateQueries({ queryKey: ["history", task.id] }),
      ]);
    },
  });
}

export const createTask = api.create.bind(api);

export function isUncertain(error: unknown) {
  return (
    error instanceof ApiError && (error.status === 0 || error.status >= 500)
  );
}
