package cl.duoc.barriodigital.requests.adapter.in.web.dto;

import cl.duoc.barriodigital.requests.domain.CivilTime;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.application.usecase.DuplicateCandidate;
import cl.duoc.barriodigital.requests.adapter.in.web.CallerContext;

import java.time.LocalDate;

/**
 * Posible duplicado tal como lo ve quien llama (EP3-75). Nunca lleva datos del solicitante, descripción, dirección,
 * referencia, coordenadas ni imagen: el Vecino no puede abrir un trámite ajeno.
 * El Vecino recibe mine y no recibe id ni hasAssignee; el personal recibe id para abrir el detalle.
 */
public record DuplicateCandidateView(
        String id,
        String folio,
        String procedureType,
        String procedureLabel,
        RequestStatus status,
        String sector,
        Integer distanceMeters,
        LocalDate createdDate,
        Boolean hasAssignee,
        Boolean mine
) {

    /** Redondeo de la distancia que se muestra; el orden usa la exacta. */
    static final int DISTANCE_STEP_METERS = 10;

    public static DuplicateCandidateView of(DuplicateCandidate candidate, CallerContext caller) {
        MunicipalRequest request = candidate.request();
        boolean staff = caller.isStaff();
        return new DuplicateCandidateView(
                staff ? request.getId() : null,
                request.getFolio(),
                request.getProcedureType(),
                request.getProcedureLabel(),
                request.getStatus(),
                request.getSector(),
                rounded(candidate.distanceMeters()),
                LocalDate.ofInstant(request.getCreatedAt(), CivilTime.ZONE),
                staff ? request.hasAssignee() : null,
                staff ? null : caller.userId().trim().equals(request.getSolicitanteId())
        );
    }

    private static Integer rounded(Double meters) {
        if (meters == null) {
            return null;
        }
        return (int) Math.round(meters / DISTANCE_STEP_METERS) * DISTANCE_STEP_METERS;
    }
}
