package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Acceso al outbox de eventos Kafka. Las marcas son UPDATE directos para no recargar el payload. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    List<OutboxEvent> findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc();

    long countByPublishedAtIsNull();

    @Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
    Optional<Instant> findOldestPendingCreatedAt();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = :publishedAt where e.eventId = :eventId and e.publishedAt is null")
    int markPublished(@Param("eventId") String eventId, @Param("publishedAt") Instant publishedAt);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update OutboxEvent e set e.attempts = e.attempts + 1, e.lastError = :lastError "
            + "where e.eventId = :eventId and e.publishedAt is null")
    int markFailed(@Param("eventId") String eventId, @Param("lastError") String lastError);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :limit")
    int deletePublishedBefore(@Param("limit") Instant limit);
}
