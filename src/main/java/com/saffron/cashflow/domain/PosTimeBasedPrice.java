package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A time-window price rule for a menu item — used for Happy Hours and
 * lunch specials. When the current Warsaw time falls within [startTime, endTime]
 * on a matching day, the POS substitutes {@code effectivePrice} for the item's
 * standard {@code sellPrice}.
 */
@Entity
@Table(name = "pos_time_based_price",
        indexes = @Index(name = "ix_pos_time_price_item", columnList = "menu_item_id"))
public class PosTimeBasedPrice {

    @Id
    private String id;

    @Column(name = "menu_item_id", nullable = false, length = 36)
    private String menuItemId;

    /** Display label shown in the POS badge, e.g. "Happy Hour". */
    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "effective_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal effectivePrice;

    /** Start of the active window (inclusive), e.g. 16:00. */
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** End of the active window (exclusive), e.g. 19:00. */
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /**
     * Comma-separated day abbreviations: MON,TUE,WED,THU,FRI,SAT,SUN.
     * Empty/null = active every day.
     */
    @Column(name = "days_of_week", length = 40)
    private String daysOfWeek;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMenuItemId() { return menuItemId; }
    public void setMenuItemId(String menuItemId) { this.menuItemId = menuItemId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getEffectivePrice() { return effectivePrice; }
    public void setEffectivePrice(BigDecimal effectivePrice) { this.effectivePrice = effectivePrice; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
    public String getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(String daysOfWeek) { this.daysOfWeek = daysOfWeek; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
