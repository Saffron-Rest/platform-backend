package com.saffron.cashflow.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;

@Entity
@Table(name = "system_setting")
public class SystemSetting {

    @Id
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> value;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Map<String, Object> getValue() { return value; }
    public void setValue(Map<String, Object> value) { this.value = value; }
}
