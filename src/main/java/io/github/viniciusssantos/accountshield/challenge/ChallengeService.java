package io.github.viniciusssantos.accountshield.challenge;

public interface ChallengeService {

    ChallengePlan create(CreateChallengeCommand command);

    ChallengeResult verify(ChallengeVerificationCommand command);

    ChallengePlan consume(ConsumeChallengeCommand command);
}
