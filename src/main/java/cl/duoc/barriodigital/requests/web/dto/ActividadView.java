package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.ActivityVisibility;
import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import cl.duoc.barriodigital.requests.domain.RequestStatus;

import java.time.Instant;
import java.util.List;

/** Lectura del expediente: marcas, tiempos que sí se pueden calcular y límites de lo que no. */
public record ActividadView(
        String requestId,
        List<EventoView> eventos,
        TiemposView tiempos,
        ContactoView contacto,
        List<String> limites
) {

    public record EventoView(
            String id,
            Instant occurredAt,
            ActivityKind kind,
            ActivityVisibility visibility,
            String actorId,
            String actorName,
            String actorRole,
            String summary,
            RequestStatus fromStatus,
            RequestStatus toStatus,
            String reason,
            ContactChannel channel,
            ContactResult result,
            String note,
            Instant nextAttemptAt,
            String interventionReason,
            String assigneeId,
            String assigneeName,
            String previousAssigneeId,
            String previousAssigneeName
    ) {
    }

    public record EtapaView(RequestStatus estado, Instant desde, Instant hasta, long segundos) {
    }

    /**
     * Los segundos nulos no se inventan como cero.
     * desdeElIngresoSegundos existe cuando el trámite sigue abierto, porque createdAt es una marca real.
     * primeraAsignacionSegundos es solo para staff: alta hasta la primera asignación.
     */
    public record TiemposView(
            Long primeraAtencionSegundos,
            Long primeraAsignacionSegundos,
            Long gestionMunicipalSegundos,
            Long desdeElIngresoSegundos,
            Long totalResolucionSegundos,
            List<EtapaView> etapas,
            List<String> noCalculado
    ) {
    }

    /** Solo para personal. El vecino recibe null: un intento interno no se le muestra. */
    public record ContactoView(
            int intentos,
            Instant ultimoIntento,
            Instant siguienteIntento,
            String siguienteAccion
    ) {
    }
}
