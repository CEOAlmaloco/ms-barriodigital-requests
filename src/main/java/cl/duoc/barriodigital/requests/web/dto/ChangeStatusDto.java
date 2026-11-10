package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body para cambiar el estado de un trámite.
 * El estado se interpreta en el servicio. El motivo es obligatorio solo si el estado es RECHAZADO y no supera 500 caracteres.
 * interventionReason es obligatorio cuando opera el Admin (EP3-04, sección 3) y se ignora para el Funcionario.
 */
public record ChangeStatusDto(
        String status,
        @Size(max = 500) String rejectionReason,
        @Size(max = 500) String interventionReason
) {
    /** Cambio sin motivo de intervención, el caso del Funcionario. */
    public ChangeStatusDto(String status, String rejectionReason) {
        this(status, rejectionReason, null);
    }
}
