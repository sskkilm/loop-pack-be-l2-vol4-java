package com.loopers.domain.like;

import com.loopers.domain.outbox.OutboxEvent;

public record LikedEvent(String eventId, Long productId) implements OutboxEvent {

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
        return LikeEventType.LIKED_EVENT.name();
    }
}
