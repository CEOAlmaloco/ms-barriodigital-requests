package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.TestListQueries;
import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Listado paginado contra la consulta real en H2 (EP3-30): página, orden, búsqueda, filtros y aislamiento del Vecino.
 * No valida el entorno Oracle desplegado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({RequestService.class, FolioCounterService.class, DomainEventRecorder.class, ClockConfig.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestListH2Test {

    private static final Instant BASE = Instant.parse("2026-11-02T15:00:00Z");
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario", "Ana");
    private static final CallerContext VECINO_A = new CallerContext("vecino-a", "Vecino", "A");

    @Autowired
    RequestService service;
    @Autowired
    MunicipalRequestRepository repository;

    @MockBean
    EventPublisher publisher;

    /** Folio que asigna cada test, para conocer su orden. Año 2098, distinto de TestFolios. */
    private int nextFolio;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        nextFolio = 0;
    }

    @Test
    void entregaLaPaginaConSusTotalesYUnaPaginaFueraDeRangoVacia() {
        for (int minute = 1; minute <= 5; minute++) {
            save("r" + minute, minute, RequestStatus.INGRESADO);
        }

        Page<MunicipalRequest> first = list(query().size(2));
        Page<MunicipalRequest> last = list(query().size(2).page(2));
        Page<MunicipalRequest> beyond = list(query().size(2).page(3));

        assertEquals(List.of("r5", "r4"), titles(first));
        assertEquals(5, first.getTotalElements());
        assertEquals(3, first.getTotalPages());
        assertEquals(List.of("r1"), titles(last));
        assertEquals(List.of(), titles(beyond));
        assertEquals(5, beyond.getTotalElements());
    }

    @Test
    void unaPaginaCuyoOffsetNoCabeEnUnIntRespondeVaciaConElTotal() {
        save("a", 1, RequestStatus.INGRESADO);
        save("b", 2, RequestStatus.RESUELTO);

        Page<MunicipalRequest> page = list(query().page(Integer.MAX_VALUE).size(50).sort("status"));

        assertEquals(List.of(), titles(page));
        assertEquals(2, page.getTotalElements());
        assertEquals(Integer.MAX_VALUE, page.getNumber());
    }

    @Test
    void losEmpatesDeFechaNoRepitenNiPierdenTramitesEntrePaginas() {
        for (int i = 1; i <= 5; i++) {
            save("t" + i, 0, RequestStatus.INGRESADO);
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(ids(list(query().size(2).page(page))));
        }

        assertEquals(5, seen.size());
        assertEquals(new HashSet<>(ids(list(query().size(50)))), new HashSet<>(seen));
    }

    @Test
    void sortStatusSigueElCicloDeVidaYNoAfectaElTotal() {
        save("rechazado", 1, RequestStatus.RECHAZADO);
        save("ingresadoAntiguo", 2, RequestStatus.INGRESADO);
        save("terreno", 3, RequestStatus.EN_TERRENO);
        save("resuelto", 4, RequestStatus.RESUELTO);
        save("admitido", 5, RequestStatus.ADMITIDO);
        save("gestion", 6, RequestStatus.EN_GESTION);
        save("ingresadoNuevo", 7, RequestStatus.INGRESADO);

        // La primera página viene llena, así que Spring corre la consulta de conteo con la Specification del orden.
        Page<MunicipalRequest> first = list(query().sort("status").size(4));
        Page<MunicipalRequest> second = list(query().sort("status,asc").size(4).page(1));
        Page<MunicipalRequest> desc = list(query().sort("status,desc").size(7));

        assertEquals(List.of("ingresadoNuevo", "ingresadoAntiguo", "admitido", "gestion"), titles(first));
        assertEquals(7, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertEquals(List.of("terreno", "resuelto", "rechazado"), titles(second));
        assertEquals(List.of("rechazado", "resuelto", "terreno", "gestion", "admitido", "ingresadoNuevo",
                "ingresadoAntiguo"), titles(desc));
    }

    @Test
    void ordenaPorFolioYPorFechaEnLasDosDirecciones() {
        save("folio3", 1, RequestStatus.INGRESADO, 3);
        save("folio1", 2, RequestStatus.INGRESADO, 1);
        save("folio2", 3, RequestStatus.INGRESADO, 2);

        assertEquals(List.of("folio1", "folio2", "folio3"), titles(list(query().sort("folio"))));
        assertEquals(List.of("folio3", "folio2", "folio1"), titles(list(query().sort("folio,desc"))));
        assertEquals(List.of("folio3", "folio1", "folio2"), titles(list(query().sort("createdAt,asc"))));
        assertEquals(List.of("folio2", "folio1", "folio3"), titles(list(query())));
    }

    @Test
    void qBuscaEnFolioDescripcionYDireccionSinDistinguirMayusculas() {
        save("luz", 1, RequestStatus.INGRESADO, 11, "vecino-a", "Poste de LUZ apagado", "Pasaje 1");
        save("aromos", 2, RequestStatus.INGRESADO, 12, "vecino-a", "Hoyo", "Calle Los Aromos 123");
        save("folio", 3, RequestStatus.INGRESADO, 777, "vecino-a", "Otro", "Pasaje 2");

        assertEquals(List.of("luz"), titles(list(query().q("luz"))));
        assertEquals(List.of("aromos"), titles(list(query().q("AROMOS"))));
        assertEquals(List.of("folio"), titles(list(query().q("bd-2098-000777"))));
        assertEquals(List.of("folio"), titles(list(query().q(" 000777 "))));
        assertEquals(List.of(), titles(list(query().q("inexistente"))));
    }

    @Test
    void qTrataLosComodinesDelLikeComoTexto() {
        save("porcentaje", 1, RequestStatus.INGRESADO, 21, "vecino-a", "Descuento 100% aplicado", "dir");
        save("mil", 2, RequestStatus.INGRESADO, 22, "vecino-a", "1000 baches", "dir");
        save("guion", 3, RequestStatus.INGRESADO, 23, "vecino-a", "calle_norte", "dir");
        save("barra", 4, RequestStatus.INGRESADO, 24, "vecino-a", "ruta\\sur", "dir");

        assertEquals(List.of("porcentaje"), titles(list(query().q("100%"))));
        assertEquals(List.of("porcentaje"), titles(list(query().q("%"))));
        assertEquals(List.of("guion"), titles(list(query().q("_"))));
        assertEquals(List.of("barra"), titles(list(query().q("\\"))));
    }

    @Test
    void openYStatusSeCombinanPorUnYLogico() {
        save("ingresado", 1, RequestStatus.INGRESADO);
        save("terreno", 2, RequestStatus.EN_TERRENO);
        save("resuelto", 3, RequestStatus.RESUELTO);
        save("rechazado", 4, RequestStatus.RECHAZADO);

        assertEquals(List.of("terreno", "ingresado"), titles(list(query().open("true"))));
        assertEquals(List.of("rechazado", "resuelto"), titles(list(query().open("false"))));
        Page<MunicipalRequest> contradictory = list(query().status(RequestStatus.RESUELTO).open("true"));
        assertEquals(List.of(), titles(contradictory));
        assertEquals(0, contradictory.getTotalElements());
        assertEquals(List.of("resuelto"), titles(list(query().status(RequestStatus.RESUELTO).open("false"))));
    }

    @Test
    void filtraPorTipoDeTramiteYSector() {
        MunicipalRequest bacheRalco = save("bacheRalco", 1, RequestStatus.INGRESADO);
        bacheRalco.placeLocation("ralco", null, null, null, null);
        MunicipalRequest luzPitril = save("luzPitril", 2, RequestStatus.INGRESADO);
        luzPitril.placeLocation("pitril", null, null, null, null);
        ReflectionTestUtils.setField(luzPitril, "procedureType", "alumbrado");
        save("luzSinSector", 3, RequestStatus.INGRESADO);
        repository.flush();

        assertEquals(List.of("luzPitril"), titles(list(query().sector("pitril"))));
        assertEquals(List.of("luzPitril"), titles(list(query().procedureType(" Alumbrado "))));
        assertEquals(List.of("luzSinSector", "bacheRalco"), titles(list(query().procedureType("bache"))));
        assertEquals(List.of(), titles(list(query().procedureType("bache").sector("pitril"))));
    }

    @Test
    void elVecinoSoloVeLosSuyosAunqueBusqueOFiltre() {
        save("mio1", 1, RequestStatus.INGRESADO, 31, "vecino-a", "Bache", "dir");
        save("mio2", 2, RequestStatus.RESUELTO, 32, "vecino-a", "Basura", "dir");
        save("ajeno", 3, RequestStatus.INGRESADO, 33, "vecino-b", "Ruido de noche", "dir");

        Page<MunicipalRequest> mine = service.list(query().build(), VECINO_A);
        Page<MunicipalRequest> openCount = service.list(query().open("true").size(1).build(), VECINO_A);

        assertEquals(List.of("mio2", "mio1"), titles(mine));
        assertEquals(2, mine.getTotalElements());
        assertEquals(1, openCount.getTotalElements());
        assertEquals(List.of(), titles(service.list(query().q("ruido").build(), VECINO_A)));
        assertEquals(List.of("ajeno"), titles(list(query().q("ruido"))));
        assertEquals(3, list(query().size(1)).getTotalElements());
    }

    private Page<MunicipalRequest> list(TestListQueries params) {
        return service.list(params.build(), FUNCIONARIO);
    }

    private MunicipalRequest save(String title, int minute, RequestStatus status) {
        return save(title, minute, status, ++nextFolio, "vecino-a", "d", "dir");
    }

    private MunicipalRequest save(String title, int minute, RequestStatus status, int folio) {
        return save(title, minute, status, folio, "vecino-a", "d", "dir");
    }

    private MunicipalRequest save(String title, int minute, RequestStatus status, int folio, String owner,
                                  String description, String address) {
        MunicipalRequest request = new MunicipalRequest(title, description, "bache", address, owner);
        ReflectionTestUtils.setField(request, "createdAt", BASE.plusSeconds(60L * minute));
        request.assignFolio(Folio.format(2098, folio));
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private static List<String> titles(Page<MunicipalRequest> page) {
        return page.getContent().stream().map(MunicipalRequest::getTitle).toList();
    }

    private static List<String> ids(Page<MunicipalRequest> page) {
        return page.getContent().stream().map(MunicipalRequest::getId).toList();
    }
}
