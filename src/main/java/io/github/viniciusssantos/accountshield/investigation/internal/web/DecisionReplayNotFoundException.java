package io.github.viniciusssantos.accountshield.investigation.internal.web;

final class DecisionReplayNotFoundException extends RuntimeException {

    DecisionReplayNotFoundException() {
        super("Decision replay is unavailable.");
    }
}
