package io.github.viniciusssantos.accountshield.outbox;

public interface OutboxEventPublisher {

    void publish(OutboxMessage message);
}
