package io.github.viniciusssantos.accountshield.risk;

public final class UnknownAlgorithmVersionException extends RuntimeException {

    private final String algorithmVersion;

    public UnknownAlgorithmVersionException(String algorithmVersion) {
        super("no risk algorithm implementation is registered for version: " + algorithmVersion);
        this.algorithmVersion = algorithmVersion;
    }

    public String algorithmVersion() {
        return algorithmVersion;
    }
}
