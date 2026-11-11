package cl.duoc.barriodigital.requests.web.dto;

import cl.duoc.barriodigital.requests.domain.ContactChannel;
import cl.duoc.barriodigital.requests.domain.ContactResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Gestión que declara el funcionario o el Admin. El autor no viaja en este body.
 * interventionReason es obligatorio cuando la registra el Admin (EP3-04, sección 3) y se ignora para el Funcionario.
 */
public record RegisterGestionDto(
        @NotNull ContactChannel channel,
        @NotNull ContactResult result,
        @Size(max = 500) String note,
        Instant nextAttemptAt,
        @Size(max = 500) String interventionReason
) {
    /** Gestión sin motivo de intervención, el caso del Funcionario. */
    public RegisterGestionDto(ContactChannel channel, ContactResult result, String note, Instant nextAttemptAt) {
        this(channel, result, note, nextAttemptAt, null);
    }
}
