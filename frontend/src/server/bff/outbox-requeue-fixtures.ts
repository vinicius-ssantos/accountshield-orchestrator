import { BffError } from "./foundation";
import type { OutboxRequeueService } from "./outbox-requeue-core";

// Deterministic, permissive demo service: fixtures mode never calls the backend, so it never
// enforces the real "must currently be DEAD_LETTERED" guard the live client does -- any UUID
// succeeds except the one reserved id below, kept specifically to exercise the
// OUTBOX_EVENT_NOT_DEAD_LETTERED error path in fixtures/e2e without a running backend.
export const FIXTURE_NOT_DEAD_LETTERED_EVENT_ID = "00000000-0000-4000-b000-000000000001";

export const fixtureOutboxRequeueService: OutboxRequeueService = {
  async requeue(input) {
    if (input.eventId === FIXTURE_NOT_DEAD_LETTERED_EVENT_ID) {
      throw new BffError(
        "OUTBOX_EVENT_NOT_DEAD_LETTERED",
        409,
        "This outbox event is no longer dead-lettered and cannot be requeued.",
      );
    }
    return { requeued: true };
  },
};
