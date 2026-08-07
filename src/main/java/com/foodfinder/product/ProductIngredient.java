package com.foodfinder.product;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One row per ingredient per product, ordered by {@code position}
 * (the legal order under EU 1169/2011 Art. 18: descrescator dupa
 * greutate). The composite key is (product_id, position).
 *
 * <p>Allergens are stored both as a boolean flag ({@code is_allergen})
 * and a free-text {@code allergen_code} (one of the 14 EU Anex II
 * codes, allowlist enforced at the application layer). The flag is
 * denormalized for index-only reads; the code is the source of truth
 * for the consumer app's bold/icon overlay.</p>
 */
@Entity
@Table(name = "product_ingredient")
public class ProductIngredient {

    @EmbeddedId
    private ProductIngredientId id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "is_allergen", nullable = false)
    private boolean isAllergen;

    @Column(name = "allergen_code", length = 20)
    private String allergenCode;

    @Column(precision = 5, scale = 2)
    private BigDecimal percentage;

    @Column(name = "origin_country", length = 2)
    private String originCountry;

    public ProductIngredient() {
    }

    public ProductIngredientId getId() {
        return id;
    }

    public void setId(ProductIngredientId id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAllergen() {
        return isAllergen;
    }

    public void setAllergen(boolean allergen) {
        isAllergen = allergen;
    }

    public String getAllergenCode() {
        return allergenCode;
    }

    public void setAllergenCode(String allergenCode) {
        this.allergenCode = allergenCode;
    }

    public BigDecimal getPercentage() {
        return percentage;
    }

    public void setPercentage(BigDecimal percentage) {
        this.percentage = percentage;
    }

    public String getOriginCountry() {
        return originCountry;
    }

    public void setOriginCountry(String originCountry) {
        this.originCountry = originCountry;
    }
}
