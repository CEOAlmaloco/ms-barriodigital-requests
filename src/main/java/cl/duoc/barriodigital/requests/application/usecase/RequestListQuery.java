package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.domain.RequestStatus;

import java.time.LocalDate;

/**
 * Parámetros de GET /api/requests tal como llegan (EP3-30). Los valida {@link RequestService#list}.
 * Un valor null o en blanco es un filtro que no se aplica.
 */
public record RequestListQuery(
        RequestStatus status,
        LocalDate from,
        LocalDate to,
        String assignee,
        String open,
        String q,
        String procedureType,
        String sector,
        String contactDue,
        Integer page,
        Integer size,
        String sort
) {
}
