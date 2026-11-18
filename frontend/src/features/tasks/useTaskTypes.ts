import { useQuery } from "@tanstack/react-query";
import { api } from "../../shared/api/client";

export function useTaskTypes() {
  return useQuery({
    queryKey: ["task-types"],
    staleTime: Infinity,
    queryFn: ({ signal }) => api.taskTypes(signal),
  });
}
