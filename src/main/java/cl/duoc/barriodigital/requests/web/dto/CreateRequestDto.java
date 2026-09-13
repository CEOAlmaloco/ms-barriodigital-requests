package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body del alta. Sin title (se autogenera) ni solicitanteId (viene del JWT vía BFF).
 */
public record CreateRequestDto(
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 100) String procedureType,
        @NotBlank @Size(max = 300) String address
) {
}
