package io.github.viniciusssantos.accountshield.challenge;

public interface ChallengeService {

    ChallengePlan create(String accountReference, ChallengeType challengeType);

    ChallengeResult verify(ChallengeVerificationCommand command);
}
