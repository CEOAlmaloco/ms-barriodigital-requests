package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.Folio;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.RequestView;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Escrituras de otra transacción entre dos lotes de la exportación (EP3-63): el CSV sale de la instantánea del inicio,
 * sin repetir ni omitir trámites. Las escrituras se confirman antes de leer el lote siguiente.
 * H2 y Oracle lo garantizan porque la exportación es un solo SELECT; no valida el entorno Oracle desplegado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({RequestService.class, FolioCounterService.class, DomainEventRecorder.class, ClockConfig.class,
        NotificationPreferenceService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RequestExportConcurrencyH2Test {

    private static final Instant BASE = Instant.parse("2026-11-02T15:00:00Z");
    private static final CallerContext FUNCIONARIO = new CallerContext("func-1", "Funcionario", "Ana");
    private static final CallerContext VECINO_A = new CallerContext("vecino-a", "Vecino", "A");

    @Autowired
    EntityManager entityManager;
    @Autowired
    RequestService service;
    @Autowired
    MunicipalRequestRepository repository;
    @Autowired
    PlatformTransactionManager transactions;

    @MockBean
    EventPublisher publisher;

    private int nextFolio;

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void lasEscriturasEntreLotesNoRepitenNiOmitenTramites() {
        nextFolio = 0;
        for (int minute = 1; minute <= 6; minute++) {
            save("r" + minute, minute, RequestStatus.INGRESADO, "vecino-a");
        }
        String closedLater = idOf("r2");
        String deletedLater = idOf("r1");
        List<String> before = ids(service.list(query().open("true").size(50).build(), FUNCIONARIO).getContent());

        List<RequestView> exported = exportWhile(FUNCIONARIO, () -> {
            // Con páginas por offset, el nuevo r7 correría r5 a la página siguiente y saldría dos veces.
            save("r7", 7, RequestStatus.INGRESADO, "vecino-a");
            MunicipalRequest closed = repository.findById(closedLater).orElseThrow();
            closed.setStatus(RequestStatus.RESUELTO);
            repository.save(closed);
            repository.deleteById(deletedLater);
        });

        assertEquals(List.of("r6", "r5", "r4", "r3", "r2", "r1"), titles(exported));
        assertEquals(before, exported.stream().map(RequestView::id).toList());
        assertEquals(RequestStatus.INGRESADO, exported.get(4).status());
        // Las escrituras sí quedaron confirmadas: una exportación nueva las ve.
        assertEquals(List.of("r7", "r6", "r5", "r4", "r3"), titles(exportWhile(FUNCIONARIO, () -> { })));
    }

    @Test
    void unTramiteAjenoCreadoEntreLotesNoLlegaAlVecino() {
        nextFolio = 100;
        for (int minute = 1; minute <= 4; minute++) {
            save("mio" + minute, minute, RequestStatus.INGRESADO, "vecino-a");
        }

        List<RequestView> exported = exportWhile(VECINO_A, () -> {
            save("ajeno", 9, RequestStatus.INGRESADO, "vecino-b");
            save("mio-nuevo", 10, RequestStatus.INGRESADO, "vecino-a");
        });

        assertEquals(List.of("mio4", "mio3", "mio2", "mio1"), titles(exported));
        assertEquals(List.of("mio-nuevo", "mio4", "mio3", "mio2", "mio1"), titles(exportWhile(VECINO_A, () -> { })));
    }

    /** Exporta con lotes de 2; después del primero, otra transacción confirma las escrituras en otro hilo. */
    private List<RequestView> exportWhile(CallerContext caller, Runnable concurrentWrites) {
        TransactionTemplate writer = new TransactionTemplate(transactions);
        RequestExportService exports = new RequestExportService(entityManager, service, 2) {
            private boolean done;

            @Override
            void afterBatch(int rowsRead) {
                if (!done) {
                    done = true;
                    CompletableFuture.runAsync(() -> writer.executeWithoutResult(status -> concurrentWrites.run()))
                            .join();
                }
            }
        };
        TransactionTemplate reader = new TransactionTemplate(transactions);
        reader.setReadOnly(true);
        return reader.execute(status -> exports.export(query().open("true").build(), caller));
    }

    private void save(String title, int minute, RequestStatus status, String owner) {
        MunicipalRequest request = new MunicipalRequest(title, "d", "bache", "dir", owner);
        ReflectionTestUtils.setField(request, "createdAt", BASE.plusSeconds(60L * minute));
        request.assignFolio(Folio.format(2097, ++nextFolio));
        request.setStatus(status);
        repository.saveAndFlush(request);
    }

    private String idOf(String title) {
        return repository.findAll().stream().filter(r -> r.getTitle().equals(title)).findFirst().orElseThrow().getId();
    }

    private static List<String> ids(List<MunicipalRequest> requests) {
        return requests.stream().map(MunicipalRequest::getId).toList();
    }

    private static List<String> titles(List<RequestView> rows) {
        return rows.stream().map(RequestView::title).toList();
    }
}
