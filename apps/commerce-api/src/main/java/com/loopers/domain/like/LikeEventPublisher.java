package com.loopers.domain.like;

public interface LikeEventPublisher {
    void publish(ProductLikedEvent event);

    void publish(ProductUnlikedEvent event);
}
