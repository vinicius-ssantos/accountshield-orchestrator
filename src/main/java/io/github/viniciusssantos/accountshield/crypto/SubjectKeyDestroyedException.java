package io.github.viniciusssantos.accountshield.crypto;

public class SubjectKeyDestroyedException extends RuntimeException {

    private final String subjectId;

    public SubjectKeyDestroyedException(String subjectId) {
        super("subject key has been destroyed by crypto-shredding and can no longer encrypt "
                + "new values: " + subjectId);
        this.subjectId = subjectId;
    }

    public String subjectId() {
        return subjectId;
    }
}
