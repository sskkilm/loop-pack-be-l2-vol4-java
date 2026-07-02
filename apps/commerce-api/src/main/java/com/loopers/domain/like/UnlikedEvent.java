package com.loopers.domain.like;

import com.loopers.domain.outbox.OutboxEvent;

public record UnlikedEvent(String eventId, Long productId) implements OutboxEvent {

    @Override
    public String aggregateType() {
        return "Like";
    }

    @Override
    public String aggregateId() {
        return String.valueOf(productId);
    }

    @Override
    public String eventType() {
        return LikeEventType.UNLIKED_EVENT.name();
    }
}
