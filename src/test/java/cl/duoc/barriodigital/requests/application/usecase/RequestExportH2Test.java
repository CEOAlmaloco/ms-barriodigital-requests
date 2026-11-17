package cl.duoc.barriodigital.requests.application.usecase;

import cl.duoc.barriodigital.requests.TestListQueries;
import cl.duoc.barriodigital.requests.infrastructure.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.adapter.out.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.adapter.out.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.adapter.out.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.adapter.in.web.CallerContext;
import cl.duoc.barriodigital.requests.adapter.in.web.dto.RequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exportación contra la consulta real en H2 (EP3-63): mismos filtros, visibilidad y proyección que el listado,
 * y el tope de filas sin recorte. No valida el entorno Oracle desplegado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({RequestService.class, RequestExportService.class, FolioCounterService.class, DomainEventRecorder.class,
        ClockConfig.class, NotificationPreferenceService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestExportH2Test {

    private static final Instant BASE = Instant.parse("2026-11-02T15:00:00Z");
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario", "Ana");
    private static final CallerContext ADMIN = new CallerContext("admin-1", "Admin", "Jefa");
    private static final CallerContext AUDITOR = new CallerContext("aud-1", "Auditor", "Control");
    private static final CallerContext VECINO_A = new CallerContext("vecino-a", "Vecino", "A");

    @Autowired
    RequestExportService exports;
    @Autowired
    RequestService service;
    @Autowired
    MunicipalRequestRepository repository;

    @MockBean
    EventPublisher publisher;

    private int nextFolio;

    @BeforeEach
    void clean() {
        repository.deleteAll();
        nextFolio = 0;
    }

    @Test
    void entregaLosMismosTramitesYElMismoOrdenQueElListado() {
        save("mio-abierto", 1, RequestStatus.INGRESADO, "vecino-a", "Poste sin luz");
        save("mio-cerrado", 2, RequestStatus.RESUELTO, "vecino-a", "Basura");
        save("ajeno-abierto", 3, RequestStatus.EN_GESTION, "vecino-b", "Poste caído");
        save("ajeno-rechazado", 4, RequestStatus.RECHAZADO, "vecino-b", "Ruido");
        assign(save("asignado", 5, RequestStatus.EN_TERRENO, "vecino-c", "Hoyo"), "func-1", "Ana");

        for (TestListQueries filter : List.of(query(), query().open("true"), query().open("false"),
                query().q("poste"), query().status(RequestStatus.EN_TERRENO), query().assignee("me"),
                query().assignee("none"), query().sort("status"), query().sort("folio,desc"))) {
            for (CallerContext caller : List.of(FUNCIONARIO, ADMIN, AUDITOR)) {
                assertEquals(listed(filter, caller), exportedIds(filter, caller));
            }
        }
        for (TestListQueries filter : List.of(query(), query().open("true"), query().q("poste"), query().sort("status"))) {
            assertEquals(listed(filter, VECINO_A), exportedIds(filter, VECINO_A));
        }
    }

    @Test
    void elVecinoSoloExportaLosSuyosAunqueBusqueTextoAjeno() {
        save("mio", 1, RequestStatus.INGRESADO, "vecino-a", "Bache");
        save("ajeno", 2, RequestStatus.INGRESADO, "vecino-b", "Ruido de noche");
        save("ajeno-folio", 3, RequestStatus.RESUELTO, "vecino-b", "Otro");

        assertEquals(List.of("mio"), titles(exports.export(query().build(), VECINO_A)));
        assertEquals(List.of(), titles(exports.export(query().q("ruido").build(), VECINO_A)));
        assertEquals(List.of(), titles(exports.export(query().q("bd-2098-000003").build(), VECINO_A)));
        assertEquals(List.of(), titles(exports.export(query().open("false").build(), VECINO_A)));
        assertEquals(List.of(), titles(exports.export(query().build(), new CallerContext("vecino-z", "Vecino"))));
    }

    @Test
    void elVecinoRecibeSuProyeccionSinDatosReservadosAlPersonal() {
        assign(save("mio", 1, RequestStatus.EN_GESTION, "vecino-a", "Bache"), "func-1", "Ana Funcionaria");

        RequestView row = exports.export(query().build(), VECINO_A).get(0);

        assertTrue(row.hasAssignee());
        assertNull(row.assigneeId());
        assertNull(row.assigneeName());
        assertEquals("vecino-a@correo.cl", row.solicitanteEmail());
        String csv = new String(RequestCsvWriter.write(List.of(row)), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(!csv.contains("Ana Funcionaria") && !csv.contains("func-1"), csv);
    }

    @Test
    void elVecinoNoPuedeUsarElFiltroAssignee() {
        save("mio", 1, RequestStatus.INGRESADO, "vecino-a", "Bache");

        for (String assignee : List.of("me", "none", "otro")) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> exports.export(query().assignee(assignee).build(), VECINO_A));
            assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
            assertEquals("El filtro assignee es solo para staff", error.getReason());
        }
    }

    @Test
    void elAuditorNoRecibeElCorreoYElPersonalSi() {
        assign(save("t", 1, RequestStatus.EN_GESTION, "vecino-a", "Bache"), "func-1", "Ana");

        RequestView auditor = exports.export(query().build(), AUDITOR).get(0);
        RequestView admin = exports.export(query().build(), ADMIN).get(0);

        assertNull(auditor.solicitanteEmail());
        assertEquals("Ana", auditor.assigneeName());
        assertEquals("vecino-a@correo.cl", admin.solicitanteEmail());
        assertEquals("Ana", admin.assigneeName());
    }

    @Test
    void validaLosParametrosComoElListado() {
        assertBadRequest(query().sort("title"), "sort no es valido");
        assertBadRequest(query().open("si"), "open debe ser true o false");
        assertBadRequest(query().sector("marte"), "sector no es valido");
        assertBadRequest(query().q("x".repeat(101)), "q no puede superar 100 caracteres");
        ResponseStatusException sinIdentidad = assertThrows(ResponseStatusException.class,
                () -> exports.export(query().build(), new CallerContext(null, "Funcionario")));
        assertEquals(HttpStatus.BAD_REQUEST, sinIdentidad.getStatusCode());
    }

    @Test
    void sinCoincidenciasEntregaUnaListaVacia() {
        save("t", 1, RequestStatus.INGRESADO, "vecino-a", "Bache");

        assertEquals(List.of(), exports.export(query().q("inexistente").build(), FUNCIONARIO));
    }

    @Test
    void exportaHastaElTopeYPorEncimaRechazaSinEntregarFilas() {
        List<MunicipalRequest> batch = new ArrayList<>();
        for (int i = 1; i <= RequestExportService.EXPORT_MAX_ROWS; i++) {
            batch.add(build("t" + i, i, RequestStatus.INGRESADO, "vecino-a", "d"));
        }
        repository.saveAllAndFlush(batch);

        List<RequestView> all = exports.export(query().build(), FUNCIONARIO);
        assertEquals(RequestExportService.EXPORT_MAX_ROWS, all.size());
        assertEquals(RequestExportService.EXPORT_MAX_ROWS, all.stream().map(RequestView::id).distinct().count());

        save("sobre-el-tope", 0, RequestStatus.INGRESADO, "vecino-b", "Sobre el tope");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> exports.export(query().build(), FUNCIONARIO));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("La exportacion supera 5000 tramites. Agrega filtros", error.getReason());
        // Un filtro que deja el resultado bajo el tope vuelve a exportar.
        assertEquals(List.of("sobre-el-tope"), titles(exports.export(query().q("sobre").build(), FUNCIONARIO)));
    }

    private void assertBadRequest(TestListQueries filter, String reason) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> exports.export(filter.build(), FUNCIONARIO));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals(reason, error.getReason());
    }

    private List<String> listed(TestListQueries filter, CallerContext caller) {
        return service.list(filter.size(50).build(), caller).getContent().stream().map(MunicipalRequest::getId).toList();
    }

    private List<String> exportedIds(TestListQueries filter, CallerContext caller) {
        return exports.export(filter.size(null).build(), caller).stream().map(RequestView::id).toList();
    }

    private MunicipalRequest save(String title, int minute, RequestStatus status, String owner, String description) {
        return repository.saveAndFlush(build(title, minute, status, owner, description));
    }

    private MunicipalRequest build(String title, int minute, RequestStatus status, String owner, String description) {
        MunicipalRequest request = new MunicipalRequest(title, description, "bache", "dir", owner, "Nombre " + owner,
                owner + "@correo.cl");
        ReflectionTestUtils.setField(request, "createdAt", BASE.plusSeconds(60L * minute));
        request.assignFolio(Folio.format(2098, ++nextFolio));
        request.setStatus(status);
        return request;
    }

    private void assign(MunicipalRequest request, String assigneeId, String assigneeName) {
        request.assignTo(assigneeId, assigneeName);
        repository.saveAndFlush(request);
    }

    private static List<String> titles(List<RequestView> rows) {
        return rows.stream().map(RequestView::title).toList();
    }
}
