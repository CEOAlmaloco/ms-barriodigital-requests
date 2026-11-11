package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.Size;

/**
 * Body para asignar o reasignar un trámite (contrato EP3-05, sección 3.2).
 * reason es obligatorio al reasignar y se ignora en la primera asignación. Su tope se valida siempre,
 * antes de conocer el trámite, igual que rejectionReason.
 */
public record AssignRequestDto(
        String assigneeId,
        @Size(max = 500, message = "reason no puede superar 500 caracteres") String reason
) {
}
