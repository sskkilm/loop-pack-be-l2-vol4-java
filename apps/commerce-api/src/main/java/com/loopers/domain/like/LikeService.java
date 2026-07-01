package com.loopers.domain.like;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeService {

    private final LikeRepository likeRepository;
    private final LikeEventPublisher eventPublisher;

    public List<Long> getLikedProductIds(Long userId) {
        return likeRepository.findAllByUserId(userId).stream()
                .map(LikeModel::getProductId)
                .toList();
    }

    public LikeResult register(Long userId, Long productId) {
        if (!likeRepository.save(new LikeModel(userId, productId))) {
            return LikeResult.IGNORED;
        }
        eventPublisher.publish(new ProductLikedEvent(productId));
        return LikeResult.APPLIED;
    }

    public LikeResult cancel(Long userId, Long productId) {
        if (!likeRepository.deleteByUserIdAndProductId(userId, productId)) {
            return LikeResult.IGNORED;
        }
        eventPublisher.publish(new ProductUnlikedEvent(productId));
        return LikeResult.APPLIED;
    }
}
