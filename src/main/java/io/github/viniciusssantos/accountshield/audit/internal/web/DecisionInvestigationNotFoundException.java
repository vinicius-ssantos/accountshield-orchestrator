package io.github.viniciusssantos.accountshield.audit.internal.web;

final class DecisionInvestigationNotFoundException extends RuntimeException {

    DecisionInvestigationNotFoundException() {
        super("Decision investigation is unavailable.");
    }
}
