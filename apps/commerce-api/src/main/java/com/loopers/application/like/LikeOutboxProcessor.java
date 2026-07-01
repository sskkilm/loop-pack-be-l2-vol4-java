package com.loopers.application.like;

import com.loopers.domain.like.LikeOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ③ 릴레이: PENDING outbox를 주기적으로 재처리한다 — at-least-once의 실제 보증자.
// LikeOutboxEventListener의 fast-path(②)도 같은 process()를 호출한다 — markDoneIfPending으로 멱등하므로 중복 호출해도 안전하다.
@Slf4j
@RequiredArgsConstructor
@Component
public class LikeOutboxProcessor {

    private final LikeOutboxService likeOutboxService;
    private final LikeFacade likeFacade;

    @Scheduled(fixedDelay = 1000)
    public void process() {
        likeOutboxService.findPending().forEach(outbox -> {
            try {
                likeFacade.reflectLikeCountChange(outbox.getId(), outbox.getProductId(), outbox.getEventType());
            } catch (Exception e) {
                log.error("outbox 반영 실패. id={}, eventType={}", outbox.getId(), outbox.getEventType(), e);
            }
        });
    }
}
