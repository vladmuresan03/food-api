package com.foodfinder.product;

import com.foodfinder.common.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The seven EU 1169/2011 Anex XIV mandatory nutrition fields plus an
 * optional fibre, tied 1:1 to a {@link Product} via {@code product_id}
 * as the primary key. 1:1 (not embedded) so it can be loaded lazily
 * by the public API and so the {@code products} table stays narrow
 * for the menu list view.
 *
 * <p>All nutrient values are nullable: a "?" in the consumer UI means
 * the restaurant has not declared the value. The basis column tells
 * the consumer whether the numbers are per 100g, per 100ml, or per
 * declared portion.</p>
 */
@Entity
@Table(name = "product_nutrition")
public class ProductNutrition extends Timestamped {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false, length = 10)
    private String basis = "per_100g";

    @Column(name = "energy_kcal", precision = 7, scale = 2)
    private BigDecimal energyKcal;

    @Column(name = "fat_g", precision = 6, scale = 2)
    private BigDecimal fatG;

    @Column(name = "sat_fat_g", precision = 6, scale = 2)
    private BigDecimal satFatG;

    @Column(name = "carbs_g", precision = 6, scale = 2)
    private BigDecimal carbsG;

    @Column(name = "sugars_g", precision = 6, scale = 2)
    private BigDecimal sugarsG;

    @Column(name = "protein_g", precision = 6, scale = 2)
    private BigDecimal proteinG;

    @Column(name = "salt_g", precision = 6, scale = 3)
    private BigDecimal saltG;

    @Column(name = "fiber_g", precision = 6, scale = 2)
    private BigDecimal fiberG;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getBasis() {
        return basis;
    }

    public void setBasis(String basis) {
        this.basis = basis;
    }

    public BigDecimal getEnergyKcal() {
        return energyKcal;
    }

    public void setEnergyKcal(BigDecimal energyKcal) {
        this.energyKcal = energyKcal;
    }

    public BigDecimal getFatG() {
        return fatG;
    }

    public void setFatG(BigDecimal fatG) {
        this.fatG = fatG;
    }

    public BigDecimal getSatFatG() {
        return satFatG;
    }

    public void setSatFatG(BigDecimal satFatG) {
        this.satFatG = satFatG;
    }

    public BigDecimal getCarbsG() {
        return carbsG;
    }

    public void setCarbsG(BigDecimal carbsG) {
        this.carbsG = carbsG;
    }

    public BigDecimal getSugarsG() {
        return sugarsG;
    }

    public void setSugarsG(BigDecimal sugarsG) {
        this.sugarsG = sugarsG;
    }

    public BigDecimal getProteinG() {
        return proteinG;
    }

    public void setProteinG(BigDecimal proteinG) {
        this.proteinG = proteinG;
    }

    public BigDecimal getSaltG() {
        return saltG;
    }

    public void setSaltG(BigDecimal saltG) {
        this.saltG = saltG;
    }

    public BigDecimal getFiberG() {
        return fiberG;
    }

    public void setFiberG(BigDecimal fiberG) {
        this.fiberG = fiberG;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }
}
