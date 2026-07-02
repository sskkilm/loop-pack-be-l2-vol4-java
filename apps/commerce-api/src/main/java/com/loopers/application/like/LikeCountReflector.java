package com.loopers.application.like;

import com.loopers.domain.like.LikeEventType;
import com.loopers.domain.outbox.OutboxService;
import com.loopers.domain.product.ProductStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// fast-path(리스너②)와 릴레이(③)가 공유하는 좋아요 수 반영 로직.
// markDoneIfPending(eventId)와 increase/decreaseLikeCount를 같은 트랜잭션으로 묶어 멱등성을 보장한다.
// 중복 이벤트가 들어와도 markDoneIfPending이 false를 반환하면 조기 종료한다.
@RequiredArgsConstructor
@Component
public class LikeCountReflector {

    private final OutboxService outboxService;
    private final ProductStatsService productStatsService;

    @Transactional
    public void reflect(String eventId, Long productId, LikeEventType eventType) {
        if (!outboxService.markDoneIfPending(eventId)) {
            return;
        }
        if (eventType == LikeEventType.LIKED_EVENT) {
            productStatsService.increaseLikeCount(productId);
        } else if (eventType == LikeEventType.UNLIKED_EVENT) {
            productStatsService.decreaseLikeCount(productId);
        }
    }
}
