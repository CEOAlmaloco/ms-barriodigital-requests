package cl.duoc.barriodigital.requests.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Nombres de la topología (contrato EP2-02). Las mismas claves que notify.
 * Si falta una clave, la app no arranca.
 */
@Validated
@ConfigurationProperties(prefix = "barriodigital.rabbit")
public record RabbitNamesProperties(
        @Valid @NotNull Exchange exchange,
        @Valid @NotNull Queue queue,
        @Valid @NotNull Routing routing
) {

    public record Exchange(
            @NotBlank String topic,
            @NotBlank String direct,
            @NotBlank String dead
    ) {
    }

    public record Queue(
            @NotBlank String email,
            @NotBlank String emailDlq,
            @NotBlank String crew,
            @NotBlank String crewDlq,
            @NotBlank String certificate,
            @NotBlank String certificateDlq
    ) {
    }

    public record Routing(
            @NotBlank String emailSend,
            @NotBlank String emailTopic,
            @NotBlank String crewTicket,
            @NotBlank String crewTopic,
            @NotBlank String certificateGen,
            @NotBlank String certificateTopic,
            @NotBlank String emailDead,
            @NotBlank String crewDead,
            @NotBlank String certificateDead
    ) {
    }
}
