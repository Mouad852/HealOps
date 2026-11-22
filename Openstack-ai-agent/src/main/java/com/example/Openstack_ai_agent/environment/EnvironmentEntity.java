package com.example.Openstack_ai_agent.environment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "environments")
public class EnvironmentEntity {
    @Id
    @Column(length = 100)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String kind;

    @Column(name = "display_label", nullable = false, length = 200)
    private String displayLabel;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> capabilities;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EnvironmentEntity() {
    }

    public static EnvironmentEntity create(
            String id,
            String name,
            String kind,
            String displayLabel,
            boolean enabled,
            Map<String, Object> capabilities
    ) {
        EnvironmentEntity entity = new EnvironmentEntity();
        entity.id = id;
        entity.name = name;
        entity.kind = kind;
        entity.displayLabel = displayLabel;
        entity.enabled = enabled;
        entity.capabilities = capabilities == null ? Map.of() : new LinkedHashMap<>(capabilities);
        entity.createdAt = OffsetDateTime.now();
        return entity;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getDisplayLabel() { return displayLabel; }
    public boolean isEnabled() { return enabled; }
    public Map<String, Object> getCapabilities() { return capabilities == null ? Map.of() : Map.copyOf(capabilities); }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
