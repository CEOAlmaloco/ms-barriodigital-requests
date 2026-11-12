package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body de la encuesta de satisfacción (EP3-74).
 * score es obligatorio (1..5). comment es opcional y no supera 500 caracteres.
 */
public record RateRequestDto(
        @NotNull(message = "score es obligatorio")
        @Min(value = 1, message = "score debe estar entre 1 y 5")
        @Max(value = 5, message = "score debe estar entre 1 y 5")
        Integer score,
        @Size(max = 500) String comment
) {
}
