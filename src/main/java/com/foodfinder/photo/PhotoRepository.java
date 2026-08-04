package com.foodfinder.photo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhotoRepository extends JpaRepository<Photo, Long> {

    Optional<Photo> findByPhotoKey(String photoKey);

    boolean existsByPhotoKey(String photoKey);

    List<Photo> findByRestaurantIdAndStatus(Long restaurantId, PhotoStatus status);

    List<Photo> findByRestaurantIdAndProductIdAndStatus(Long restaurantId, Long productId, PhotoStatus status);

    Optional<Photo> findFirstByProductIdAndPrimaryPhotoTrue(Long productId);

    Optional<Photo> findFirstByRestaurantIdAndProductIdIsNullAndPrimaryPhotoTrue(Long restaurantId);
}
