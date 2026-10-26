package cl.duoc.barriodigital.requests.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento que publica requests.
 * Lleva el tipo, un eventId nuevo, la hora, el traceId, el correlationId (id del trámite) y el payload.
 */
public record EventEnvelope(
        String type,
        UUID eventId,
        Instant timestamp,
        UUID traceId,
        String correlationId,
        Object payload
) {
}
