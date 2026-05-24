package com.loopers.application.product;

import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.SortType;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductFinderTest {

    private ProductFinder productFinder;
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productFinder = new ProductFinder(productRepository);
    }

    @DisplayName("상품을 ID로 조회할 때,")
    @Nested
    class GetById {

        @DisplayName("존재하는 ID라면 상품이 반환된다.")
        @Test
        void returnsProduct_whenProductExists() {
            // given
            Long id = 1L;
            ProductModel product = new ProductModel(10L, "테스트 상품", BigDecimal.valueOf(1000), 0L);
            when(productRepository.find(id)).thenReturn(Optional.of(product));

            // when
            ProductModel result = productFinder.getById(id);

            // then
            assertThat(result).isSameAs(product);
        }

        @DisplayName("존재하지 않는 ID라면 NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductDoesNotExist() {
            // given
            Long id = 999L;
            when(productRepository.find(id)).thenReturn(Optional.empty());

            // when
            CoreException result = assertThrows(CoreException.class, () -> productFinder.getById(id));

            // then
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품 목록을 조회할 때,")
    @Nested
    class GetAll {

        @DisplayName("정렬 기준별로 상품 목록이 반환된다.")
        @ParameterizedTest
        @EnumSource(SortType.class)
        void returnsProducts_whenProductsExist(SortType sortType) {
            // given
            List<ProductModel> products = List.of(
                    new ProductModel(10L, "상품A", BigDecimal.valueOf(2000), 0L),
                    new ProductModel(10L, "상품B", BigDecimal.valueOf(1000), 0L)
            );
            when(productRepository.findAll(sortType)).thenReturn(products);

            // when
            List<ProductModel> result = productFinder.getAll(sortType);

            // then
            assertThat(result).isSameAs(products);
        }

        @DisplayName("상품이 없으면 빈 목록이 반환된다.")
        @Test
        void returnsEmptyList_whenNoProductsExist() {
            // given
            when(productRepository.findAll(SortType.LATEST)).thenReturn(List.of());

            // when
            List<ProductModel> result = productFinder.getAll(SortType.LATEST);

            // then
            assertThat(result).isEmpty();
        }
    }
}
