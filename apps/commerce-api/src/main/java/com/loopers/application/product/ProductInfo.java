package com.loopers.application.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;

import java.math.BigDecimal;

public record ProductInfo(
    Long id,
    String brandName,
    String name,
    BigDecimal price,
    Long likeCount
) {
    public static ProductInfo from(BrandModel brand, ProductModel product) {
        return new ProductInfo(
            product.getId(),
            brand.getName(),
            product.getName(),
            product.getPrice(),
            product.getLikeCount()
        );
    }
}
