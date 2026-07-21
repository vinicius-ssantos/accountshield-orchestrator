package io.github.viniciusssantos.accountshield.challenge;

import java.util.UUID;

public interface ChallengeService {

    ChallengePlan create(String accountReference, ChallengeType challengeType);

    ChallengeResult verify(ChallengeVerificationCommand command);

    ChallengePlan verifyIdentityForRecovery(UUID challengeId);
}
