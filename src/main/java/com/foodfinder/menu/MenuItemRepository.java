package com.foodfinder.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    List<MenuItem> findByMenuIdOrderBySortOrderAsc(Long menuId);

    List<MenuItem> findByProductId(Long productId);

    Optional<MenuItem> findByMenuIdAndProductId(Long menuId, Long productId);

    boolean existsByMenuIdAndProductId(Long menuId, Long productId);

    long countByMenuId(Long menuId);

    long countByRestaurantId(Long restaurantId);
}
