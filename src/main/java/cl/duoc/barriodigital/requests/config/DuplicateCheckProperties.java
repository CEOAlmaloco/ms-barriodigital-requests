package cl.duoc.barriodigital.requests.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Radio y tope de la consulta de posibles duplicados (EP3-75). Si faltan o salen del rango, el servicio no arranca.
 * El radio no lo elige el cliente: uno grande permitiría recorrer la comuna.
 */
@Validated
@ConfigurationProperties(prefix = "barriodigital.duplicates")
public record DuplicateCheckProperties(
        @NotNull @Min(10) @Max(2000) Integer radiusMeters,
        @NotNull @Min(1) @Max(20) Integer maxResults
) {
}
