package io.github.viniciusssantos.accountshield.investigation.internal.web;

final class PolicyInvestigationNotFoundException extends RuntimeException {

    PolicyInvestigationNotFoundException() {
        super("Policy investigation is unavailable.");
    }
}
