package com.foodfinder.photo;

import com.foodfinder.common.Timestamped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A photo belongs to a restaurant and may optionally be associated with one
 * product of that restaurant (composite FK enforces same-restaurant).
 * Exactly one of storageKey / externalUrl is populated (DB CHECK).
 */
@Entity
@Table(name = "photo")
public class Photo extends Timestamped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "photo_key", nullable = false, length = 160, unique = true)
    private String photoKey;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private PhotoSourceType sourceType;

    @Column(name = "storage_key", columnDefinition = "text")
    private String storageKey;

    @Column(name = "external_url", columnDefinition = "text")
    private String externalUrl;

    @Column(name = "thumbnail_storage_key", columnDefinition = "text")
    private String thumbnailStorageKey;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "alt_text", length = 300)
    private String altText;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryPhoto = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoStatus status = PhotoStatus.ACTIVE;

    @Column(length = 64)
    private String sha256;

    public Long getId() {
        return id;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public void setPhotoKey(String photoKey) {
        this.photoKey = photoKey;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public PhotoSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(PhotoSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public String getThumbnailStorageKey() {
        return thumbnailStorageKey;
    }

    public void setThumbnailStorageKey(String thumbnailStorageKey) {
        this.thumbnailStorageKey = thumbnailStorageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getAltText() {
        return altText;
    }

    public void setAltText(String altText) {
        this.altText = altText;
    }

    public boolean isPrimaryPhoto() {
        return primaryPhoto;
    }

    public void setPrimaryPhoto(boolean primaryPhoto) {
        this.primaryPhoto = primaryPhoto;
    }

    public PhotoStatus getStatus() {
        return status;
    }

    public void setStatus(PhotoStatus status) {
        this.status = status;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }
}
