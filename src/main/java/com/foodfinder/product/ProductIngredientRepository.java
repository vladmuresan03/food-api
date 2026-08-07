package com.foodfinder.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductIngredientRepository
        extends JpaRepository<ProductIngredient, ProductIngredientId> {

    /** All ingredients for a product, ordered by their position. */
    List<ProductIngredient> findByIdProductIdOrderByIdPositionAsc(Long productId);

    /**
     * Wipe all ingredients for a product. Used by the CSV importer and
     * the admin REST "replace all" path: simpler than diffing the
     * existing list against the incoming one, and the data set is
     * bounded (max ~50 ingredients per product).
     *
     * <p>{@code clearAutomatically=true} drops the JPA persistence
     * context so subsequent {@code save()} calls in the same
     * transaction don't try to UPDATE entities the bulk delete
     * already removed.</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ProductIngredient i where i.id.productId = :productId")
    int deleteByProductId(@Param("productId") Long productId);
}
