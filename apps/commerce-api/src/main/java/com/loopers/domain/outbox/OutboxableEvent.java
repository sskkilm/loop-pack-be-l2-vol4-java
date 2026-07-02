package com.loopers.domain.outbox;

public interface OutboxEvent {

    String eventId();

    String aggregateType();

    String aggregateId();

    String eventType();
}
