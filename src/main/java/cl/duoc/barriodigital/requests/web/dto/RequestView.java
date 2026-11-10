package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.web.CallerContext;

import java.time.Instant;

/**
 * Trámite tal como lo ve quien llama (contrato EP3-04, sección 6.1).
 * Conserva los nombres de campo del JSON de la entidad, así que el BFF y el frontend no cambian.
 * El Auditor no recibe el correo del solicitante y el Vecino no recibe quién es el responsable.
 */
public record RequestView(
        String id,
        String folio,
        String title,
        String description,
        String procedureType,
        String procedureLabel,
        String address,
        String sector,
        String locationReference,
        Double latitude,
        Double longitude,
        LocationPrecision locationPrecision,
        String solicitanteId,
        String solicitanteNombre,
        String solicitanteEmail,
        RequestStatus status,
        String rejectionReason,
        boolean hasAttachment,
        Instant createdAt,
        Instant updatedAt,
        boolean hasAssignee,
        String assigneeId,
        String assigneeName
) {

    /** Arma la vista según el rol de quien llama. */
    public static RequestView of(MunicipalRequest request, CallerContext caller) {
        boolean auditor = caller != null && caller.isAuditor();
        return new RequestView(
                request.getId(),
                request.getFolio(),
                request.getTitle(),
                request.getDescription(),
                request.getProcedureType(),
                request.getProcedureLabel(),
                request.getAddress(),
                request.getSector(),
                request.getLocationReference(),
                request.getLatitude(),
                request.getLongitude(),
                request.getLocationPrecision(),
                request.getSolicitanteId(),
                request.getSolicitanteNombre(),
                auditor ? null : request.getSolicitanteEmail(),
                request.getStatus(),
                request.getRejectionReason(),
                request.isHasAttachment(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                // El responsable llega con las columnas de asignación; hasta entonces ningún trámite lo tiene.
                false,
                null,
                null
        );
    }
}
