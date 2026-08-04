package com.foodfinder.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    Optional<Restaurant> findByRestaurantKey(String restaurantKey);

    boolean existsByRestaurantKey(String restaurantKey);
}
