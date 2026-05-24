package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private ProductService productService;
    private ProductRepository productRepository;
    private ProductFinder productFinder;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productFinder = mock(ProductFinder.class);
        productService = new ProductService(productRepository, productFinder);
    }

    @DisplayName("상품을 생성할 때,")
    @Nested
    class Create {

        @DisplayName("정상 입력이라면 저장된 상품이 반환된다.")
        @Test
        void createsProduct_whenValidInputIsProvided() {
            // given
            ProductModel expected = new ProductModel(1L, "테스트 상품", BigDecimal.valueOf(1000), 50L);
            when(productRepository.save(any(ProductModel.class))).thenReturn(expected);

            // when
            ProductModel result = productService.create(1L, "테스트 상품", BigDecimal.valueOf(1000), 50L);

            // then
            assertThat(result).isSameAs(expected);
        }
    }

    @DisplayName("상품을 수정할 때,")
    @Nested
    class Update {

        @DisplayName("존재하는 상품이라면 저장된 상품이 반환된다.")
        @Test
        void updatesProduct_whenProductExists() {
            // given
            Long id = 1L;
            ProductModel product = new ProductModel(10L, "원래 상품", BigDecimal.valueOf(1000), 10L);
            when(productFinder.getById(id)).thenReturn(product);
            when(productRepository.save(product)).thenReturn(product);

            // when
            ProductModel result = productService.update(id, "수정된 상품", BigDecimal.valueOf(2000), 20L);

            // then
            assertThat(result).isSameAs(product);
        }
    }

    @DisplayName("상품을 삭제할 때,")
    @Nested
    class Delete {

        @DisplayName("존재하는 상품이라면 예외 없이 완료된다.")
        @Test
        void deletesProduct_whenProductExists() {
            // given
            Long id = 1L;
            ProductModel product = new ProductModel(10L, "상품", BigDecimal.valueOf(1000), 10L);
            when(productFinder.getById(id)).thenReturn(product);
            when(productRepository.save(product)).thenReturn(product);

            // when & then
            assertDoesNotThrow(() -> productService.delete(id));
        }
    }
}
