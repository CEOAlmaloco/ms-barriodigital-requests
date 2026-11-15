package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.config.DuplicateCheckConfig;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.LocationPrecision;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.RequestsErrorCode;
import cl.duoc.barriodigital.requests.web.RequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Posibles duplicados contra la consulta real en H2 (EP3-75): radio, sector, estados abiertos, orden, tope y roles.
 * Radio de 150 m y tope de 5, los de application.yml de test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({DuplicateCheckService.class, DuplicateCheckConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class DuplicateCheckH2Test {

    private static final double LAT = -37.9;
    private static final double LON = -71.6;
    private static final String LAT_TEXT = String.valueOf(LAT);
    private static final String LON_TEXT = String.valueOf(LON);
    private static final Instant BASE = Instant.parse("2026-11-10T15:00:00Z");
    private static final CallerContext VECINO = new CallerContext("vecino-1", "Vecino");
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario");
    private static final CallerContext ADMIN = new CallerContext("admin-1", "Admin");

    @Autowired DuplicateCheckService service;
    @Autowired MunicipalRequestRepository repository;

    private int nextFolio;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        nextFolio = 0;
    }

    @Test
    void entraLoQueEstaDentroDelRadioYQuedaFueraLoQueLoSupera() {
        save("dentro", "bache", north(149.5), null, 1);
        save("fuera", "bache", north(150.5), null, 2);
        save("otroTipo", "alumbrado", north(10), null, 3);

        List<DuplicateCandidate> found = nearPoint("bache", null);

        assertEquals(List.of("dentro"), titles(found));
        assertEquals(149.5, found.get(0).distanceMeters(), 0.01);
    }

    @Test
    void elTipoSeNormalizaComoEnElAlta() {
        save("dentro", "bache", north(20), null, 1);

        assertEquals(List.of("dentro"), titles(service.forNewRequest(" BACHE ", LAT_TEXT, LON_TEXT, null, VECINO)));
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"RESUELTO", "RECHAZADO", "CANCELADO"})
    void losCerradosNoSonDuplicados(RequestStatus closed) {
        save("cerrado", "bache", north(10), "ralco", 1).setStatus(closed);
        repository.flush();

        assertTrue(nearPoint("bache", "ralco").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"INGRESADO", "ADMITIDO", "EN_GESTION", "EN_TERRENO"})
    void losAbiertosSiSonDuplicados(RequestStatus open) {
        save("abierto", "bache", north(10), null, 1).setStatus(open);
        repository.flush();

        assertEquals(List.of("abierto"), titles(nearPoint("bache", null)));
    }

    @Test
    void primeroPorDistanciaYDespuesPorSectorConFechaDescendente() {
        save("sectorAntiguo", "bache", null, "ralco", 1);
        save("lejos", "bache", north(120), "ralco", 2);
        save("sectorReciente", "bache", null, "ralco", 3);
        save("cerca", "bache", north(30), "ralco", 4);

        assertEquals(List.of("cerca", "lejos", "sectorReciente", "sectorAntiguo"), titles(nearPoint("bache", "ralco")));
    }

    @Test
    void conPuntoElSectorSoloSumaLosQueNoTienenPunto() {
        save("conPuntoLejos", "bache", north(400), "ralco", 1);
        save("sinPunto", "bache", null, "ralco", 2);

        assertEquals(List.of("sinPunto"), titles(nearPoint("bache", "ralco")));
    }

    @Test
    void sinPuntoElSectorIncluyeTambienLosQueTienenPunto() {
        save("conPunto", "bache", north(400), "ralco", 1);
        save("sinPunto", "bache", null, "ralco", 2);
        save("otroSector", "bache", null, "pitril", 3);

        List<DuplicateCandidate> found = service.forNewRequest("bache", null, null, "ralco", VECINO);

        assertEquals(List.of("sinPunto", "conPunto"), titles(found));
        assertNull(found.get(0).distanceMeters());
        assertNull(found.get(1).distanceMeters());
    }

    @Test
    void elSectorOtroNoImpideBuscarPorCercania() {
        save("cerca", "bache", north(40), "otro", 1);
        save("otroSinPunto", "bache", null, "otro", 2);

        assertEquals(List.of("cerca"), titles(nearPoint("bache", "otro")));
    }

    @Test
    void elSectorOtroSinPuntoNoTieneConQueCompararYDevuelveVacio() {
        save("otroSinPunto", "bache", null, "otro", 1);

        assertTrue(service.forNewRequest("bache", null, null, "otro", VECINO).isEmpty());
    }

    @Test
    void elEmpateEnDistanciaVaPorFechaDescendenteYDespuesPorId() {
        save("antiguo", "bache", north(50), null, 1);
        save("idAlto", "bache", north(50), LON, null, 2, "00000000-0000-4000-8000-000000000002");
        save("idBajo", "bache", north(50), LON, null, 2, "00000000-0000-4000-8000-000000000001");

        assertEquals(List.of("idBajo", "idAlto", "antiguo"), titles(nearPoint("bache", null)));
    }

    @Test
    void elTopeSeAplicaDespuesDeOrdenarYNoPierdeALosMasCercanos() {
        for (int i = 0; i < 6; i++) {
            save("sector" + i, "bache", null, "ralco", i);
        }
        // Los más cercanos se guardan al final: un recorte antes de ordenar los perdería.
        for (int meters = 140; meters >= 20; meters -= 20) {
            save("a" + meters, "bache", north(meters), "ralco", 10);
        }

        assertEquals(List.of("a20", "a40", "a60", "a80", "a100"), titles(nearPoint("bache", "ralco")));
    }

    @Test
    void elVecinoTambienVeLosSuyos() {
        save("mio", "bache", north(10), null, 1);

        assertEquals(List.of("mio"), titles(nearPoint("bache", null)));
    }

    @Test
    void sinCoincidenciasDevuelveListaVacia() {
        assertTrue(nearPoint("bache", "ralco").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Funcionario", "Admin", "Auditor"})
    void laConsultaDelAltaEsSoloDelVecino(String role) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.forNewRequest("bache", LAT_TEXT, LON_TEXT, null, new CallerContext("u-1", role)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void sinIdentidadResponde400ConCodigo() {
        RequestsException ex = assertThrows(RequestsException.class,
                () -> service.forNewRequest("bache", LAT_TEXT, LON_TEXT, null, new CallerContext(" ", "Vecino")));
        assertEquals(RequestsErrorCode.USER_ID_REQUIRED, ex.code());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL, -37.9, -71.6, NULL, procedureType es obligatorio",
            "Bache!, -37.9, -71.6, NULL, 'procedureType invalido. Debe ser minusculas, numeros, guion o guion bajo (maximo 40 caracteres)'",
            "bache, -37.9, NULL, NULL, latitude y longitude van juntas",
            "bache, NULL, -71.6, ralco, latitude y longitude van juntas",
            "bache, abc, -71.6, NULL, latitude y longitude deben ser numeros",
            "bache, NaN, -71.6, NULL, latitude y longitude deben ser numeros",
            "bache, -37.9, Infinity, NULL, latitude y longitude deben ser numeros",
            "bache, -33.45, -70.66, NULL, El punto esta fuera de la comuna",
            "bache, NULL, NULL, providencia, sector no es valido",
            "bache, NULL, NULL, NULL, Indica un punto o un sector"
    }, nullValues = "NULL")
    void validaLosParametrosConMensajesEstables(
            String procedureType, String latitude, String longitude, String sector, String message
    ) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.forNewRequest(procedureType, latitude, longitude, sector, VECINO));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(message, ex.getReason());
    }

    @Test
    void elPersonalVeLosDuplicadosDeUnTramiteSinIncluirloASiMismo() {
        MunicipalRequest base = save("base", "bache", LAT, "ralco", 1);
        save("cerca", "bache", north(60), "ralco", 2);
        save("sinPunto", "bache", null, "ralco", 3);
        save("otroTipo", "basura", north(5), "ralco", 4);

        assertEquals(List.of("cerca", "sinPunto"), titles(service.forRequest(base.getId(), FUNCIONARIO)));
        assertEquals(List.of("cerca", "sinPunto"), titles(service.forRequest(base.getId(), ADMIN)));
    }

    @Test
    void unTramiteSinPuntoYDeSectorOtroNoTieneDuplicadosQueMostrar() {
        MunicipalRequest base = save("base", "bache", null, "otro", 1);
        save("otroSinPunto", "bache", null, "otro", 2);

        assertTrue(service.forRequest(base.getId(), FUNCIONARIO).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Vecino", "Auditor"})
    void losDuplicadosDeUnTramiteSonDelFuncionarioYDelAdmin(String role) {
        MunicipalRequest base = save("base", "bache", north(0), null, 1);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.forRequest(base.getId(), new CallerContext("u-1", role)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void elRolSeRevisaAntesQueLaExistenciaDelTramite() {
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> service.forRequest("no-existe", VECINO));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        RequestsException notFound = assertThrows(RequestsException.class,
                () -> service.forRequest("no-existe", FUNCIONARIO));
        assertEquals(RequestsErrorCode.REQUEST_NOT_FOUND, notFound.code());
    }

    private List<DuplicateCandidate> nearPoint(String procedureType, String sector) {
        return service.forNewRequest(procedureType, LAT_TEXT, LON_TEXT, sector, VECINO);
    }

    /** Latitud a esa distancia al norte del centro, exacta para Haversine sobre el meridiano. */
    private static Double north(double meters) {
        return LAT + Math.toDegrees(meters / GeoDistance.EARTH_RADIUS_METERS);
    }

    /** Con latitud, el punto queda en la longitud del centro. */
    private MunicipalRequest save(String title, String type, Double latitude, String sector, int minute) {
        return save(title, type, latitude, latitude == null ? null : LON, sector, minute, null);
    }

    private MunicipalRequest save(
            String title, String type, Double latitude, Double longitude, String sector, int minute, String id
    ) {
        MunicipalRequest request = new MunicipalRequest(title, "d", type, "dir", "vecino-1", "Vecino");
        if (id != null) {
            ReflectionTestUtils.setField(request, "id", id);
        }
        ReflectionTestUtils.setField(request, "createdAt", BASE.plusSeconds(60L * minute));
        request.placeLocation(sector, null, latitude, longitude, latitude == null ? null : LocationPrecision.EXACTO);
        request.assignFolio(Folio.format(2097, ++nextFolio));
        return repository.saveAndFlush(request);
    }

    private static List<String> titles(List<DuplicateCandidate> candidates) {
        return candidates.stream().map(candidate -> candidate.request().getTitle()).toList();
    }
}
