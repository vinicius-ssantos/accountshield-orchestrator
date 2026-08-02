import { describe, expect, it } from "vitest";

import { BffError } from "./foundation";
import { FIXTURE_NOT_DEAD_LETTERED_EVENT_ID, fixtureOutboxRequeueService } from "./outbox-requeue-fixtures";

describe("fixtureOutboxRequeueService", () => {
  it("succeeds for any UUID other than the reserved not-dead-lettered id", async () => {
    await expect(
      fixtureOutboxRequeueService.requeue({ eventId: "00000000-0000-4000-b000-000000000006" }, "corr-1"),
    ).resolves.toEqual({ requeued: true });
  });

  it("rejects with OUTBOX_EVENT_NOT_DEAD_LETTERED for the reserved id, to exercise the error path", async () => {
    await expect(
      fixtureOutboxRequeueService.requeue({ eventId: FIXTURE_NOT_DEAD_LETTERED_EVENT_ID }, "corr-1"),
    ).rejects.toMatchObject({ code: "OUTBOX_EVENT_NOT_DEAD_LETTERED", status: 409 });
  });

  it("rejects with a BffError instance, not a plain object", async () => {
    await expect(
      fixtureOutboxRequeueService.requeue({ eventId: FIXTURE_NOT_DEAD_LETTERED_EVENT_ID }, "corr-1"),
    ).rejects.toBeInstanceOf(BffError);
  });
});
