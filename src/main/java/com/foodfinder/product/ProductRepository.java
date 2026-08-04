package com.foodfinder.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductKey(String productKey);

    boolean existsByProductKey(String productKey);

    List<Product> findByRestaurantId(Long restaurantId);

    long countByRestaurantId(Long restaurantId);
}
