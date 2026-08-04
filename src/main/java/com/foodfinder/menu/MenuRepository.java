package com.foodfinder.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    Optional<Menu> findByMenuKey(String menuKey);

    boolean existsByMenuKey(String menuKey);

    List<Menu> findByRestaurantIdOrderByCreatedAtAsc(Long restaurantId);

    List<Menu> findByRestaurantIdAndStatus(Long restaurantId, MenuStatus status);
}
