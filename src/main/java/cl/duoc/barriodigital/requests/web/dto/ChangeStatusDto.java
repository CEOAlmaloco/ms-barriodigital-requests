package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body para cambiar el estado de un trámite.
 * El estado se interpreta en el servicio. El motivo es obligatorio solo si el estado es RECHAZADO y no supera 500 caracteres.
 */
public record ChangeStatusDto(
        String status,
        @Size(max = 500) String rejectionReason
) {
}
