import { afterEach, beforeEach, vi } from "vitest";

export const FIXED_NOW = new Date("2026-01-15T12:00:00.000Z");

export function useDeterministicClock(now: Date = FIXED_NOW): void {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(now);
  });

  afterEach(() => {
    vi.useRealTimers();
  });
}
