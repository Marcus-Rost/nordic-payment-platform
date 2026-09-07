package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sanctions_lists")
public class SanctionsListEntity {

    @Id
    @Column(name = "list_id", length = 64)
    private String listId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "source", nullable = false, length = 140)
    private String source;

    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "last_refresh_at", nullable = false)
    private Instant lastRefreshAt;

    @Column(name = "stale_after_hours", nullable = false)
    private int staleAfterHours = 24;

    protected SanctionsListEntity() {}

    public String getListId() { return listId; }
    public String getName() { return name; }
    public String getSource() { return source; }
    public String getVersion() { return version; }
    public int getEntryCount() { return entryCount; }
    public Instant getLastRefreshAt() { return lastRefreshAt; }
    public int getStaleAfterHours() { return staleAfterHours; }

    public void markRefreshed(String version, int entryCount) {
        this.version = version;
        this.entryCount = entryCount;
        this.lastRefreshAt = Instant.now();
    }
}
