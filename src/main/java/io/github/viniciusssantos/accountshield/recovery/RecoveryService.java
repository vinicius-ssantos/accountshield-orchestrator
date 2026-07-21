package io.github.viniciusssantos.accountshield.recovery;

import java.util.UUID;

public interface RecoveryService {

    RecoveryFlow initiate(InitiateRecoveryCommand command);

    RecoveryFlow confirmIdentity(ConfirmIdentityCommand command);

    RecoveryFlow complete(UUID recoveryId);

    RecoveryFlow review(RecoveryReviewCommand command);
}
