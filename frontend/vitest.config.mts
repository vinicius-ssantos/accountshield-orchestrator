import react from "@vitejs/plugin-react";
import tsconfigPaths from "vite-tsconfig-paths";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [tsconfigPaths(), react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}", "test/**/*.test.{ts,tsx}"],
    clearMocks: true,
    restoreMocks: true,
    reporters: process.env.CI ? ["default", "junit"] : ["default"],
    outputFile: process.env.CI
      ? { junit: "test-results/vitest-junit.xml" }
      : undefined,
    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary", "html"],
      reportsDirectory: "coverage",
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/**/*.d.ts",
        "src/app/**/page.tsx",
        "src/app/layout.tsx",
        "src/app/loading.tsx",
        "src/app/not-found.tsx",
      ],
    },
  },
});
