package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {

    @Query("SELECT p FROM ProductModel p JOIN FETCH p.stock WHERE p.id = :id")
    Optional<ProductModel> findByIdWithStock(@Param("id") Long id);

    @Query("SELECT p FROM ProductModel p JOIN FETCH p.stock ORDER BY p.createdAt DESC")
    List<ProductModel> findAllByOrderByCreatedAtDesc();

    @Query("SELECT p FROM ProductModel p JOIN FETCH p.stock ORDER BY p.price ASC")
    List<ProductModel> findAllByOrderByPriceAsc();

    @Query("SELECT p FROM ProductModel p JOIN FETCH p.stock ORDER BY p.likeCount DESC")
    List<ProductModel> findAllByOrderByLikeCountDesc();
}
