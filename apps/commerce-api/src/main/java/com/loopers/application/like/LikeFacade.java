package com.loopers.application.like;

import com.loopers.application.product.ProductInfo;
import com.loopers.application.product.ProductInfoAssembler;
import com.loopers.domain.like.LikeEventType;
import com.loopers.domain.like.LikeOutboxService;
import com.loopers.domain.like.LikeService;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.ProductStatsService;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class LikeFacade {

    private final LikeService likeService;
    private final ProductService productService;
    private final ProductStatsService productStatsService;
    private final LikeOutboxService likeOutboxService;
    private final ProductInfoAssembler productInfoAssembler;
    private final UserService userService;

    @Transactional
    public void like(String loginId, String loginPw, Long productId) {
        UserModel user = userService.getLoginUser(loginId, loginPw);
        ProductModel product = productService.getById(productId);
        likeService.register(user.getId(), product.getId());
    }

    @Transactional
    public void unlike(String loginId, String loginPw, Long productId) {
        UserModel user = userService.getLoginUser(loginId, loginPw);
        ProductModel product = productService.getById(productId);
        likeService.cancel(user.getId(), product.getId());
    }

    public List<ProductInfo> getLikedProducts(String loginId, String loginPw, Long userId) {
        UserModel user = userService.getLoginUser(loginId, loginPw);
        user.validateOwner(userId);
        List<Long> productIds = likeService.getLikedProductIds(userId);
        List<ProductModel> products = productService.findAllByIds(productIds);
        return productInfoAssembler.toInfoList(products);
    }

    // markDoneIfPending과 increase/decreaseLikeCount를 같은 트랜잭션으로 묶어 멱등성을 보장한다.
    // 중복 이벤트가 발행돼도 markDoneIfPending이 false를 반환하면 조기 종료한다.
    @Transactional
    public void reflectLikeCountChange(Long outboxId, Long productId, LikeEventType eventType) {
        if (!likeOutboxService.markDoneIfPending(outboxId)) {
            return;
        }
        if (eventType == LikeEventType.LIKED_EVENT) {
            productStatsService.increaseLikeCount(productId);
        } else if (eventType == LikeEventType.UNLIKED_EVENT) {
            productStatsService.decreaseLikeCount(productId);
        }
    }
}
