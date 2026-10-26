package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body para crear un trámite.
 * Pide descripción, tipo y dirección. El título lo arma el servicio y el solicitante llega por headers del BFF.
 */
public record CreateRequestDto(
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 100) String procedureType,
        @NotBlank @Size(max = 300) String address
) {
}
