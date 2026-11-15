package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.config.DuplicateCheckProperties;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.ProcedureTypes;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.RequestsErrorCode;
import cl.duoc.barriodigital.requests.web.RequestsException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.idIsNot;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.open;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.pointWithin;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.procedureTypeIs;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.sectorIs;
import static cl.duoc.barriodigital.requests.persistence.MunicipalRequestSpecifications.withoutPoint;

/**
 * Posibles duplicados (EP3-75): trámites abiertos del mismo tipo dentro del radio de un punto o, en su defecto, del
 * mismo sector. Primero van los del radio, por distancia exacta, y después los del sector, del más reciente al más
 * antiguo; los empates se ordenan por id. El tope se aplica al final, para no perder a los más cercanos.
 */
@Service
public class DuplicateCheckService {

    /** "Otro sector de la comuna" abarca demasiado para indicar un duplicado. */
    static final String NON_COMPARABLE_SECTOR = "otro";

    private static final Comparator<DuplicateCandidate> BY_DISTANCE = Comparator
            .comparingDouble((DuplicateCandidate c) -> c.distanceMeters())
            .thenComparing(c -> c.request().getCreatedAt(), Comparator.reverseOrder())
            .thenComparing(c -> c.request().getId());

    private static final Comparator<DuplicateCandidate> BY_RECENCY = Comparator
            .comparing((DuplicateCandidate c) -> c.request().getCreatedAt(), Comparator.reverseOrder())
            .thenComparing(c -> c.request().getId());

    private final MunicipalRequestRepository repository;
    private final DuplicateCheckProperties properties;

    public DuplicateCheckService(MunicipalRequestRepository repository, DuplicateCheckProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Consulta del Vecino antes de crear un trámite. Necesita un punto o un sector; con punto, el sector solo suma los
     * trámites sin punto. Un sector no comparable sin punto no tiene con qué comparar: lista vacía.
     */
    @Transactional(readOnly = true)
    public List<DuplicateCandidate> forNewRequest(
            String procedureType, String latitude, String longitude, String sector, CallerContext caller
    ) {
        requireUserId(caller);
        if (!caller.isVecino()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Solo un Vecino puede consultar posibles duplicados al crear un tramite");
        }
        String type = parseProcedureType(procedureType);
        Double lat = parseCoordinate(latitude);
        Double lon = parseCoordinate(longitude);
        if ((lat == null) != (lon == null)) {
            throw badRequest("latitude y longitude van juntas");
        }
        if (lat != null && !LocationRules.insideCommune(lat, lon)) {
            throw badRequest("El punto esta fuera de la comuna");
        }
        String parsedSector = RequestService.parseSector(sector);
        if (lat == null && parsedSector == null) {
            throw badRequest("Indica un punto o un sector");
        }
        return search(type, lat, lon, parsedSector, null);
    }

    /** Posibles duplicados de un trámite ya ingresado, para el Funcionario y el Admin. Excluye al propio trámite. */
    @Transactional(readOnly = true)
    public List<DuplicateCandidate> forRequest(String id, CallerContext caller) {
        requireUserId(caller);
        if (!caller.isFuncionario() && !caller.isAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Solo un Funcionario o un Admin puede ver los posibles duplicados");
        }
        MunicipalRequest base = repository.findById(id)
                .orElseThrow(() -> new RequestsException(RequestsErrorCode.REQUEST_NOT_FOUND));
        return search(base.getProcedureType(), base.getLatitude(), base.getLongitude(), base.getSector(), base.getId());
    }

    private List<DuplicateCandidate> search(
            String procedureType, Double latitude, Double longitude, String sector, String excludedId
    ) {
        Specification<MunicipalRequest> sameTypeOpen = Specification.where(procedureTypeIs(procedureType))
                .and(open(true))
                .and(idIsNot(excludedId));
        boolean hasPoint = latitude != null && longitude != null;
        List<DuplicateCandidate> result = new ArrayList<>();

        if (hasPoint) {
            int radius = properties.radiusMeters();
            GeoDistance.Box box = GeoDistance.boxAround(latitude, longitude, radius);
            List<DuplicateCandidate> near = new ArrayList<>();
            for (MunicipalRequest candidate : repository.findAll(
                    sameTypeOpen.and(pointWithin(box.south(), box.north(), box.west(), box.east())))) {
                double distance = GeoDistance.meters(
                        latitude, longitude, candidate.getLatitude(), candidate.getLongitude());
                if (distance <= radius) {
                    near.add(new DuplicateCandidate(candidate, distance));
                }
            }
            near.sort(BY_DISTANCE);
            result.addAll(near);
        }

        if (sector != null && !NON_COMPARABLE_SECTOR.equals(sector)) {
            // Con punto, un trámite con punto del mismo sector ya se evaluó por distancia.
            Specification<MunicipalRequest> sameSector = sameTypeOpen.and(sectorIs(sector));
            List<DuplicateCandidate> bySector = new ArrayList<>();
            for (MunicipalRequest candidate : repository.findAll(hasPoint ? sameSector.and(withoutPoint()) : sameSector)) {
                bySector.add(new DuplicateCandidate(candidate, null));
            }
            bySector.sort(BY_RECENCY);
            result.addAll(bySector);
        }

        return List.copyOf(result.subList(0, Math.min(result.size(), properties.maxResults())));
    }

    private static String parseProcedureType(String raw) {
        String type = ProcedureTypes.normalize(raw);
        if (type == null || type.isEmpty()) {
            throw badRequest("procedureType es obligatorio");
        }
        if (!ProcedureTypes.hasValidFormat(type)) {
            throw badRequest(
                    "procedureType invalido. Debe ser minusculas, numeros, guion o guion bajo (maximo 40 caracteres)");
        }
        return type;
    }

    /** Número finito o null si viene vacío. NaN e Infinity no son coordenadas. */
    private static Double parseCoordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Cae al 400 de abajo.
        }
        throw badRequest("latitude y longitude deben ser numeros");
    }

    private static void requireUserId(CallerContext caller) {
        if (caller.userId() == null || caller.userId().isBlank()) {
            throw new RequestsException(RequestsErrorCode.USER_ID_REQUIRED);
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
