package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** Componente outbox del health. Informativo: UP siempre, UNKNOWN si no puede leer la tabla. */
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxEventRepository outbox;
    private final Clock clock;

    public OutboxHealthIndicator(OutboxEventRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public Health health() {
        try {
            long pending = outbox.countByPublishedAtIsNull();
            long oldestAge = pending == 0 ? 0 : outbox.findOldestPendingCreatedAt()
                    .map(createdAt -> Math.max(0, Duration.between(createdAt, clock.instant()).getSeconds()))
                    .orElse(0L);
            return Health.up()
                    .withDetail("pendientes", pending)
                    .withDetail("antiguedadMasViejoSegundos", oldestAge)
                    .build();
        } catch (RuntimeException ex) {
            return Health.unknown().withDetail("error", "no se pudo leer outbox_events").build();
        }
    }
}
