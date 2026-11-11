package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Generated;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Evento de requests.events pendiente de publicar (contrato EP3-02, sección 2.1).
 * Se escribe en la misma transacción que el cambio del trámite y su RequestActivity. OutboxRelay lo publica.
 * La tabla la crea db/outbox-events.sql: event_sequence es identity con ORDER, que Hibernate no sabe crear.
 */
@Entity
@Table(
        name = "outbox_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_events_sequence", columnNames = "event_sequence"),
        indexes = @Index(name = "idx_outbox_pending", columnList = "published_at, event_sequence")
)
public class OutboxEvent implements Persistable<String> {

    /** Es el eventId del sobre. */
    @Id
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    /** Orden de publicación. Lo asigna Oracle al insertar; es el campo sequence del JSON publicado. */
    @Generated
    @Column(name = "event_sequence", nullable = false, insertable = false, updatable = false)
    private Long eventSequence;

    /** requestId: la key del mensaje en Kafka. */
    @Column(name = "aggregate_id", length = 36, nullable = false)
    private String aggregateId;

    @Column(name = "event_type", length = 60, nullable = false)
    private String eventType;

    /** Sobre completo de EP3-01 en JSON, sin sequence. */
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null mientras está pendiente. */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** Envíos fallidos. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String eventId, String aggregateId, String eventType, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return eventId;
    }

    /** Nuevo mientras Oracle no le asigna event_sequence: así save hace persist y no un merge con SELECT previo. */
    @Override
    public boolean isNew() {
        return eventSequence == null;
    }

    public String getEventId() {
        return eventId;
    }

    public Long getEventSequence() {
        return eventSequence;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
