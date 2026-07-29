package io.github.viniciusssantos.accountshield.investigation.internal.web;

final class DecisionTimelineNotFoundException extends RuntimeException {

    DecisionTimelineNotFoundException() {
        super("Decision investigation is unavailable.");
    }
}
