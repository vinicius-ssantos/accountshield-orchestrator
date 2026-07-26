import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import {
  ApplicationState,
  DataTable,
  MaskedIdentifier,
  StatusBadge,
  maskIdentifier,
} from "./components";

describe("MaskedIdentifier", () => {
  it("never exposes the hidden value in rendered text or attributes", () => {
    const hiddenValue = "acct_72c4b69e18f74291";
    const maskedValue = maskIdentifier(hiddenValue, 7, 4);
    const { container } = render(
      <MaskedIdentifier label="Account identifier" maskedValue={maskedValue} />,
    );

    expect(screen.getByLabelText(`Account identifier: ${maskedValue}`)).toHaveTextContent(
      maskedValue,
    );
    expect(container.textContent).not.toContain(hiddenValue);
    expect(container.innerHTML).not.toContain(hiddenValue);

    for (const element of container.querySelectorAll("*")) {
      for (const attribute of element.attributes) {
        expect(attribute.value).not.toContain(hiddenValue);
      }
    }
  });

  it("does not reveal short identifiers", () => {
    expect(maskIdentifier("abc123", 4, 4)).toBe("ab••••");
  });
});

describe("StatusBadge", () => {
  it("keeps a stable visible label instead of relying on color", () => {
    render(<StatusBadge label="Unavailable" tone="critical" />);

    expect(screen.getByText("Unavailable")).toBeVisible();
    expect(screen.getByText("Unavailable").closest("[data-tone]")).toHaveAttribute(
      "data-tone",
      "critical",
    );
  });
});

describe("DataTable", () => {
  it("renders an explicit empty state inside the table", () => {
    render(
      <DataTable
        caption="Decision results"
        columns={[{ key: "decision", label: "Decision" }]}
        rows={[]}
      />,
    );

    expect(screen.getByText("No records match the current view.")).toBeVisible();
    expect(screen.getByText("Decision results")).toHaveClass("srOnly");
  });
});

describe("ApplicationState", () => {
  it("announces loading progress without implying a result", () => {
    const { container } = render(
      <ApplicationState
        description="The query is still running."
        kind="loading"
        title="Loading decisions"
      />,
    );

    expect(container.querySelector("[aria-live='polite']")).toBeInTheDocument();
    expect(screen.getByText("loading")).toBeVisible();
    expect(screen.getByRole("heading", { name: "Loading decisions" })).toBeVisible();
  });
});
