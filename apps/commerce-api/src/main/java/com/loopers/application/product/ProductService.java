package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductFinder productFinder;

    @Transactional
    public ProductModel create(Long brandId, String name, BigDecimal price, Long quantity) {
        return productRepository.save(new ProductModel(brandId, name, price, quantity));
    }

    @Transactional
    public ProductModel update(Long id, String name, BigDecimal price, Long quantity) {
        ProductModel product = productFinder.getById(id);

        product.update(name, price, quantity);

        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        ProductModel product = productFinder.getById(id);

        product.delete();

        productRepository.save(product);
    }
}
