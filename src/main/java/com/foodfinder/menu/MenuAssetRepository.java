package com.foodfinder.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuAssetRepository extends JpaRepository<MenuAsset, Long> {

    Optional<MenuAsset> findByAssetKey(String assetKey);

    boolean existsByAssetKey(String assetKey);

    List<MenuAsset> findByMenuIdOrderBySortOrderAsc(Long menuId);

    long countByMenuId(Long menuId);
}
