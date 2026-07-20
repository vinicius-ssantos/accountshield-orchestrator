package io.github.viniciusssantos.accountshield.protection;

public interface ProtectionDecisionService {

    ProtectionDecisionResult decide(ProtectionDecisionCommand command);
}
