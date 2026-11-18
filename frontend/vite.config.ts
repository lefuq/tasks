import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: process.env.API_TARGET ?? "http://127.0.0.1:8080" },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    coverage: {
      provider: "v8",
      include: [
        "src/features/**/*.{ts,tsx}",
        "src/shared/**/*.ts",
        "src/pages/**/*.tsx",
        "src/app/App.tsx",
      ],
      exclude: ["src/**/*.test.*", "src/shared/api/schema.ts"],
      reporter: ["text", "html"],
    },
  },
});
