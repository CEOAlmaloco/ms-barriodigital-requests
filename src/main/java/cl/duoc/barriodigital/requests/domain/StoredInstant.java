package cl.duoc.barriodigital.requests.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Oracle (TIMESTAMP(6)) y H2 guardan microsegundos y redondean el resto. Un instante que va a una columna y a un
 * evento del outbox se trunca antes, para que el occurredAt del evento sea exactamente el de la base.
 */
public final class StoredInstant {

    private StoredInstant() {
    }

    public static Instant of(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant now() {
        return of(Instant.now());
    }
}
