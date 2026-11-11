package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Acceso al outbox de eventos Kafka. EP3-31 solo escribe; la lectura de pendientes es del relay (EP3-32). */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
}
