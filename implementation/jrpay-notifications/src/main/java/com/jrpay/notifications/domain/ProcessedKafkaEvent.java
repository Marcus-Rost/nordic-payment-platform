package com.jrpay.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * De-dup table for the Kafka projection consumers. {@code (topic, partition,
 * offset)} is a stable identifier for any event ever produced; we INSERT
 * before processing and rely on the unique-constraint to short-circuit
 * duplicate deliveries.
 */
@Entity
@Table(name = "processed_kafka_events")
public class ProcessedKafkaEvent {

    @EmbeddedId
    private Pk pk;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;

    protected ProcessedKafkaEvent() {
    }

    public ProcessedKafkaEvent(String topic, int partition, long offset) {
        this.pk = new Pk(topic, partition, offset);
        this.processedAt = OffsetDateTime.now();
    }

    public Pk getPk() { return pk; }

    public OffsetDateTime getProcessedAt() { return processedAt; }

    @Embeddable
    public static class Pk implements Serializable {
        @Column(name = "topic", nullable = false, length = 120)
        private String topic;

        @Column(name = "partition", nullable = false)
        private int partition;

        @Column(name = "\"offset\"", nullable = false)
        private long offset;

        public Pk() {
        }

        public Pk(String topic, int partition, long offset) {
            this.topic = topic;
            this.partition = partition;
            this.offset = offset;
        }

        public String getTopic() { return topic; }
        public int getPartition() { return partition; }
        public long getOffset() { return offset; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return partition == pk.partition && offset == pk.offset && Objects.equals(topic, pk.topic);
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, partition, offset);
        }
    }
}
