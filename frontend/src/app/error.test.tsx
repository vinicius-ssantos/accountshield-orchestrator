import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import ErrorState from "./error";

describe("ErrorState", () => {
  it("keeps error details private and retries on explicit action", async () => {
    const reset = vi.fn();
    const user = userEvent.setup();

    render(
      <ErrorState
        error={new Error("sensitive upstream failure")}
        reset={reset}
      />,
    );

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", {
        level: 1,
        name: "The operations console could not complete this request",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByText("sensitive upstream failure")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Retry" }));

    expect(reset).toHaveBeenCalledTimes(1);
    expect(
      screen.getByRole("link", { name: "Return to overview" }),
    ).toHaveAttribute("href", "/");
  });
});
