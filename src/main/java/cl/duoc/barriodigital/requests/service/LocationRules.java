package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Sector, referencia y punto del alta. El texto de la dirección sigue siendo obligatorio.
 * El punto, si viene, tiene que caer dentro de la comuna y decir si es exacto o aproximado.
 */
final class LocationRules {

    /** Caja de la comuna de Alto Biobío. Holgada para el borde del valle, no para otra región. */
    static final double NORTH = -37.60;
    static final double SOUTH = -38.25;
    static final double WEST = -71.80;
    static final double EAST = -71.00;

    static final Map<String, String> SECTORS = Map.of(
            "ralco", "Ralco",
            "villa-ralco", "Villa Ralco",
            "pitril", "Pitril",
            "caunicu", "Cauñicú",
            "quepuca", "Quepuca",
            "butalelbun", "Butalelbún",
            "malla", "Malla",
            "trapa-trapa", "Trapa Trapa",
            "el-barco", "El Barco",
            "otro", "Otro sector de la comuna"
    );

    private LocationRules() {
    }

    static void apply(MunicipalRequest request, CreateRequestDto body) {
        String sector = blankToNull(body.sector());
        if (sector != null && !SECTORS.containsKey(sector)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sector invalido.");
        }

        Double latitude = body.latitude();
        Double longitude = body.longitude();
        boolean hasLatitude = latitude != null;
        boolean hasLongitude = longitude != null;
        if (hasLatitude != hasLongitude) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La latitud y la longitud van juntas.");
        }

        LocationPrecision precision = null;
        if (hasLatitude) {
            if (latitude < SOUTH || latitude > NORTH || longitude < WEST || longitude > EAST) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El punto esta fuera de la comuna.");
            }
            precision = precisionOf(body.locationPrecision());
        } else if (blankToNull(body.locationPrecision()) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locationPrecision invalido.");
        }

        request.placeLocation(sector, blankToNull(body.locationReference()), latitude, longitude, precision);
    }

    private static LocationPrecision precisionOf(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica si el punto es exacto o aproximado.");
        }
        try {
            return LocationPrecision.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locationPrecision invalido.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
