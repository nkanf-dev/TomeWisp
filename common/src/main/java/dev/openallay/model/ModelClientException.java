package dev.openallay.model;

public class ModelClientException extends RuntimeException {
    private final ModelFailure failure;

    public ModelClientException(ModelFailure failure) {
        super(failure.code() + ": " + failure.message());
        this.failure = failure;
    }

    public ModelFailure failure() {
        return failure;
    }
}
