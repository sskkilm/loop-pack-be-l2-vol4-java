package com.loopers.interfaces.event.like;

import com.loopers.application.like.LikeOutboxProcessor;
import com.loopers.domain.like.LikeEventType;
import com.loopers.domain.like.LikeOutboxService;
import com.loopers.domain.like.ProductLikedEvent;
import com.loopers.domain.like.ProductUnlikedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@RequiredArgsConstructor
@Component
public class LikeOutboxEventListener {

    private final LikeOutboxService likeOutboxService;
    private final LikeOutboxProcessor likeOutboxProcessor;

    // ① outbox 기록: 주요 트랜잭션과 같은 Tx에 합류한다 — 기록이 실패하면 좋아요 자체도 롤백된다(원자적, 유실 차단).
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void record(ProductLikedEvent event) {
        likeOutboxService.record(event.productId(), LikeEventType.LIKED_EVENT);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void record(ProductUnlikedEvent event) {
        likeOutboxService.record(event.productId(), LikeEventType.UNLIKED_EVENT);
    }

    // ② fast-path 반영: 커밋 확정 후 비동기로 즉시 시도한다(best-effort).
    // 실패하거나 이 시점에 아직 안 끝나도 문제 없다 — LikeOutboxProcessor의 스케줄 릴레이(③)가 markDoneIfPending으로 멱등하게 다시 처리한다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(ProductLikedEvent event) {
        likeOutboxProcessor.process();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(ProductUnlikedEvent event) {
        likeOutboxProcessor.process();
    }
}
