package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "receipt_file")
public class ReceiptFile {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private DailyEntry entry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_item_id")
    private ExpenseItem expenseItem;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String path;

    private String category;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getEntryId() { return entry != null ? entry.getId() : null; }
    public void setEntry(DailyEntry entry) { this.entry = entry; }
    public ExpenseItem getExpenseItem() { return expenseItem; }
    public void setExpenseItem(ExpenseItem expenseItem) { this.expenseItem = expenseItem; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Instant getCreatedAt() { return createdAt; }
}
