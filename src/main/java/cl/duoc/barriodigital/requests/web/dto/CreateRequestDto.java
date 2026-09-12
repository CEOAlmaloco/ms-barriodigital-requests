package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateRequestDto(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String procedureType
) {
}
