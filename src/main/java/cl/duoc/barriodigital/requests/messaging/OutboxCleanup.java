package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Borra a diario los eventos publicados hace más de 7 días. Los pendientes no se borran nunca. */
@Component
public class OutboxCleanup {

    public static final String CRON = "0 30 3 * * *";
    public static final String ZONE = "America/Santiago";
    static final Duration RETENTION = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxEventRepository outbox;
    private final Clock clock;

    public OutboxCleanup(OutboxEventRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public int purgePublished() {
        Instant limit = clock.instant().minus(RETENTION);
        int deleted = outbox.deletePublishedBefore(limit);
        log.info("outbox-limpieza eliminados={} publicadosAntesDe={}", deleted, limit);
        return deleted;
    }
}
