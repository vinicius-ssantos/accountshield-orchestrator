import { describe, expect, it } from "vitest";

import { FIXED_NOW, useDeterministicClock } from "./deterministic-clock";

describe("deterministic clock", () => {
  useDeterministicClock();

  it("freezes time for reproducible scenarios", () => {
    expect(new Date()).toEqual(FIXED_NOW);
    expect(Date.now()).toBe(FIXED_NOW.getTime());
  });
});
