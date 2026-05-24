package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.SortType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ProductRepositoryImpl implements ProductRepository {
    private final ProductJpaRepository productJpaRepository;

    @Override
    public ProductModel save(ProductModel product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Optional<ProductModel> find(Long id) {
        return productJpaRepository.findByIdWithStock(id);
    }

    @Override
    public List<ProductModel> findAll(SortType sortType) {
        return switch (sortType) {
            case LATEST -> productJpaRepository.findAllByOrderByCreatedAtDesc();
            case PRICE_ASC -> productJpaRepository.findAllByOrderByPriceAsc();
            case LIKES_DESC -> productJpaRepository.findAllByOrderByLikeCountDesc();
        };
    }
}
