package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A sellable item on the menu. Carries:
 *  - retail price (what the guest pays, incl. VAT)
 *  - food cost (theoretical ingredient cost) — optional but required for margin analytics
 *  - VAT rate, used to back out net revenue for the P&amp;L
 *  - SKU (matched against incoming POS webhooks for sales aggregation)
 */
@Entity
@Table(name = "menu_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_menu_item_sku", columnNames = "sku"),
        indexes = {
                @Index(name = "ix_menu_item_category", columnList = "category_id"),
                @Index(name = "ix_menu_item_active", columnList = "is_active")
        })
public class MenuItem {

    @Id
    private String id;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Column(nullable = false, length = 160)
    private String name;

    /** Stable identifier sent by the POS webhook so we can link sales reliably. */
    @Column(length = 64)
    private String sku;

    /** EAN-13 / EAN-8 barcode for scanner-based ordering. */
    @Column(length = 20)
    private String barcode;

    @Column(length = 500)
    private String description;

    /** Gross (VAT-inclusive) retail price. */
    @Column(name = "sell_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal sellPrice;

    /** Theoretical ingredient cost per unit (also gross of VAT to match sellPrice). */
    @Column(name = "food_cost", precision = 12, scale = 2)
    private BigDecimal foodCost;

    /** Polish standard VAT for restaurant food is 8 %, drinks/alcohol 23 %. */
    @Column(name = "vat_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRatePct = new BigDecimal("8.00");

    /** Optional longer-form text shown on the printable menu (e.g. "Slow-braised lamb shoulder, saffron rice, sour barberries"). */
    @Column(name = "long_description", length = 1000)
    private String longDescription;

    /** Relative path under {@code app.upload-dir} (e.g. "menu/abc.jpg"). Served via /uploads/. */
    @Column(name = "image_path", length = 255)
    private String imagePath;

    /** Comma-separated dietary tags, e.g. "vegetarian,vegan,gluten-free,spicy,signature". */
    @Column(name = "dietary_tags", length = 255)
    private String dietaryTags;

    /** Comma-separated allergens, e.g. "gluten,dairy,nuts,sesame,eggs". */
    @Column(length = 255)
    private String allergens;

    /** Whether to feature this item on the printable menu (e.g. chef's recommendation). */
    @Column(name = "featured", nullable = false, columnDefinition = "boolean default false")
    private boolean featured = false;

    /** Whether this item should appear on the Saffron POS ordering screen. */
    @Column(name = "pos_available", nullable = false, columnDefinition = "boolean default true")
    private boolean posAvailable = true;

    /** Display order on the POS grid within its category. Lower numbers appear first. */
    @Column(name = "pos_display_order", nullable = false, columnDefinition = "integer default 0")
    private int posDisplayOrder = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getSellPrice() { return sellPrice; }
    public void setSellPrice(BigDecimal sellPrice) { this.sellPrice = sellPrice; }
    public BigDecimal getFoodCost() { return foodCost; }
    public void setFoodCost(BigDecimal foodCost) { this.foodCost = foodCost; }
    public BigDecimal getVatRatePct() { return vatRatePct; }
    public void setVatRatePct(BigDecimal vatRatePct) { this.vatRatePct = vatRatePct; }
    public String getLongDescription() { return longDescription; }
    public void setLongDescription(String longDescription) { this.longDescription = longDescription; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getDietaryTags() { return dietaryTags; }
    public void setDietaryTags(String dietaryTags) { this.dietaryTags = dietaryTags; }
    public String getAllergens() { return allergens; }
    public void setAllergens(String allergens) { this.allergens = allergens; }
    public boolean isFeatured() { return featured; }
    public void setFeatured(boolean featured) { this.featured = featured; }
    public boolean isPosAvailable() { return posAvailable; }
    public void setPosAvailable(boolean posAvailable) { this.posAvailable = posAvailable; }
    public int getPosDisplayOrder() { return posDisplayOrder; }
    public void setPosDisplayOrder(int posDisplayOrder) { this.posDisplayOrder = posDisplayOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
