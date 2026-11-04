package cl.duoc.barriodigital.requests.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body para crear un trámite.
 * Pide descripción, tipo y dirección. Sector, referencia y punto son opcionales.
 * El título lo arma el servicio y el solicitante llega por headers del BFF.
 */
public record CreateRequestDto(
        @NotBlank @Size(max = 2000) String description,
        @NotBlank @Size(max = 100) String procedureType,
        @NotBlank @Size(max = 300) String address,
        @Size(max = 40) String sector,
        @Size(max = 200) String locationReference,
        Double latitude,
        Double longitude,
        @Size(max = 20) String locationPrecision
) {
    /** Alta sin punto. Los trámites anteriores y las pruebas siguen usando solo la dirección. */
    public CreateRequestDto(String description, String procedureType, String address) {
        this(description, procedureType, address, null, null, null, null, null);
    }
}
