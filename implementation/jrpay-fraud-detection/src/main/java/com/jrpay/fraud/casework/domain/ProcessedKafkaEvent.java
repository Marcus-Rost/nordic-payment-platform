package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "processed_kafka_events")
@IdClass(ProcessedKafkaEvent.Pk.class)
public class ProcessedKafkaEvent {

    @Id
    @Column(name = "topic", length = 140)
    private String topic;

    @Id
    @Column(name = "partition")
    private int partition;

    @Id
    @Column(name = "kafka_offset")
    private long kafkaOffset;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedKafkaEvent() {}

    public ProcessedKafkaEvent(String topic, int partition, long kafkaOffset) {
        this.topic = topic;
        this.partition = partition;
        this.kafkaOffset = kafkaOffset;
        this.processedAt = Instant.now();
    }

    public String getTopic() { return topic; }
    public int getPartition() { return partition; }
    public long getKafkaOffset() { return kafkaOffset; }
    public Instant getProcessedAt() { return processedAt; }

    public static class Pk implements Serializable {
        private String topic;
        private int partition;
        private long kafkaOffset;

        public Pk() {}
        public Pk(String topic, int partition, long kafkaOffset) {
            this.topic = topic;
            this.partition = partition;
            this.kafkaOffset = kafkaOffset;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return partition == pk.partition && kafkaOffset == pk.kafkaOffset
                    && Objects.equals(topic, pk.topic);
        }

        @Override
        public int hashCode() { return Objects.hash(topic, partition, kafkaOffset); }
    }
}
