import { defineConfig } from "@playwright/test";

if (
  process.env.TEST_ISOLATED !== "1" ||
  !process.env.TEST_API_URL ||
  !process.env.TEST_EMULATOR_URL
) {
  throw new Error(
    "Запускайте через python3 scripts/test-e2e.py: требуется отдельное тестовое окружение.",
  );
}

export default defineConfig({
  testDir: "./e2e",
  timeout: 240000,
  expect: { timeout: 90000 },
  workers: 1,
  use: {
    baseURL: "http://127.0.0.1:4173",
    timezoneId: "Asia/Krasnoyarsk",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "mobile-390", use: { viewport: { width: 390, height: 844 } } },
    { name: "desktop-1440", use: { viewport: { width: 1440, height: 1000 } } },
  ],
  webServer: {
    command:
      "node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
  },
});
