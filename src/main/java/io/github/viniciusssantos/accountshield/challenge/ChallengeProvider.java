package io.github.viniciusssantos.accountshield.challenge;

public interface ChallengeProvider {

    String generateCode();

    boolean verifyCode(String providedCode, String expectedCode);
}
