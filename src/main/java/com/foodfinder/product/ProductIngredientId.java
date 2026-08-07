package com.foodfinder.product;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link ProductIngredient}: a product has many
 * ingredients, each at a unique {@code position} (1..N). The natural
 * key is (product_id, position) because position is the legal order
 * under EU 1169/2011 Art. 18 (descrescator dupa greutate).
 */
@Embeddable
public class ProductIngredientId implements Serializable {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "position", nullable = false)
    private Short position;

    public ProductIngredientId() {
    }

    public ProductIngredientId(Long productId, Short position) {
        this.productId = productId;
        this.position = position;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Short getPosition() {
        return position;
    }

    public void setPosition(Short position) {
        this.position = position;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductIngredientId that)) return false;
        return Objects.equals(productId, that.productId) && Objects.equals(position, that.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, position);
    }
}
