import { readFrontendEnvironment } from "./config/environment";

export function register(): void {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    readFrontendEnvironment(process.env, "runtime");
  }
}
