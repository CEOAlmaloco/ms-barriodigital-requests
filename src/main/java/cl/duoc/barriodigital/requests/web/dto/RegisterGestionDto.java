package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Gestión que declara el funcionario. El autor no viaja en este body. */
public record RegisterGestionDto(
        @NotNull ContactChannel channel,
        @NotNull ContactResult result,
        @Size(max = 500) String note,
        Instant nextAttemptAt
) {
}
