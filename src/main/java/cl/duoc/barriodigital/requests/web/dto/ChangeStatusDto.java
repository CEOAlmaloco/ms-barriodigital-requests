package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body de PUT /api/requests/{id}/status (EP1.5-01 / EP1.5-05).
 * status se parsea en el service (400 si no es del enum).
 * rejectionReason es opcional acá obligatorio solo si status es RECHAZADO.
 */
public record ChangeStatusDto(
        String status,
        @Size(max = 500) String rejectionReason
) {
}
