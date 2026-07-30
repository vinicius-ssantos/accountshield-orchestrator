package io.github.viniciusssantos.accountshield.recovery.internal.web;

final class RecoveryInvestigationNotFoundException extends RuntimeException {

    RecoveryInvestigationNotFoundException() {
        super("Recovery investigation is unavailable.");
    }
}
