package io.github.viniciusssantos.accountshield.audit;

public interface DecisionTraceRecorder {

    void record(DecisionTraceCommand command);
}
