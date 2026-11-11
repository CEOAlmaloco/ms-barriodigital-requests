package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.TestFolios;
import cl.duoc.barriodigital.requests.config.ClockConfig;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.AssignRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static cl.duoc.barriodigital.requests.TestListQueries.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asignación contra JPA real en H2 (contrato EP3-05, sección 3.5): bloqueo de la fila, filtro del listado y
 * el CHECK del autor. No valida el entorno Oracle desplegado.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestService.class, FolioCounterService.class, RequestAssignmentService.class, StaffMemberService.class,
        ClockConfig.class, DomainEventRecorder.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestAssignmentH2Test {

    private static final CallerContext ANA = new CallerContext("oid-ana", "Funcionario", "Ana");
    private static final CallerContext BRUNO = new CallerContext("oid-bruno", "Funcionario", "Bruno");
    private static final CallerContext ADMIN = new CallerContext("oid-admin", "Admin", "Admin");

    @Autowired RequestAssignmentService assignment;
    @Autowired RequestService requests;
    @Autowired StaffMemberService staff;
    @Autowired MunicipalRequestRepository repository;
    @Autowired RequestActivityRepository activity;
    @Autowired PlatformTransactionManager transactions;
    @MockBean EventPublisher publisher;

    @BeforeEach
    void staffList() {
        // Cada test parte de una base sin trámites, para que el filtro se compare exacto.
        activity.deleteAll();
        repository.deleteAll();
        staff.touch("oid-ana", "Ana", "Funcionario");
        staff.touch("oid-bruno", "Bruno", "Funcionario");
    }

    @Test
    void elFiltroAssigneeCorreEnLaConsultaReal() {
        MunicipalRequest mine = save(RequestStatus.INGRESADO);
        MunicipalRequest other = save(RequestStatus.INGRESADO);
        MunicipalRequest unassigned = save(RequestStatus.INGRESADO);
        assignment.take(mine.getId(), ANA);
        assignment.take(other.getId(), BRUNO);

        List<String> me = ids(requests.list(query().assignee(" me ").build(), ANA).getContent());
        List<String> none = ids(requests.list(query().assignee("none").build(), ANA).getContent());
        List<String> adminMe = ids(requests.list(query().assignee("me").build(), ADMIN).getContent());

        assertEquals(List.of(mine.getId()), me);
        assertTrue(none.contains(unassigned.getId()));
        assertTrue(!none.contains(mine.getId()) && !none.contains(other.getId()));
        assertEquals(List.of(), adminMe);
    }

    @Test
    void elHitoSinActorPasaElCheckYUnaMarcaSinAutorNo() {
        MunicipalRequest request = save(RequestStatus.INGRESADO);

        assignment.take(request.getId(), ANA);

        assertEquals(2, activity.findByRequestIdOrderByOccurredAtAsc(request.getId()).size());
        RequestActivity withoutActor = RequestActivity.statusChanged(request.getId(), Instant.now(), null, null, null,
                RequestStatus.INGRESADO, RequestStatus.ADMITIDO, null, null);
        assertThrows(DataIntegrityViolationException.class, () -> activity.saveAndFlush(withoutActor));
    }

    @Test
    void dosFuncionariosTomanElMismoTramiteYElSegundoRecibe409() throws Exception {
        MunicipalRequest request = save(RequestStatus.INGRESADO);
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                assignment.take(request.getId(), ANA);
                taken.countDown();
                await(commit);
                return 200;
            }));
            assertTrue(taken.await(5, TimeUnit.SECONDS));
            var second = pool.submit(statusOf(() -> assignment.take(request.getId(), BRUNO)));
            // La primera toma sigue abierta: la segunda espera el bloqueo de la fila.
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            commit.countDown();

            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals("409 El tramite ya tiene responsable", second.get(5, TimeUnit.SECONDS));
            assertEquals("oid-ana", repository.findById(request.getId()).orElseThrow().getAssigneeId());
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unaReasignacionYUnCambioDeEstadoDelResponsableAnteriorElSegundoRecibe403() throws Exception {
        MunicipalRequest request = save(RequestStatus.INGRESADO);
        assignment.take(request.getId(), ANA);
        CountDownLatch reassigned = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                assignment.assign(request.getId(), new AssignRequestDto("oid-bruno", "Carga de trabajo"), ADMIN);
                reassigned.countDown();
                await(commit);
                return 200;
            }));
            assertTrue(reassigned.await(5, TimeUnit.SECONDS));
            var second = pool.submit(statusOf(() -> requests.changeStatus(request.getId(), "ADMITIDO", null, null, ANA)));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            commit.countDown();

            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals("403 Solo el responsable del tramite puede operarlo", second.get(5, TimeUnit.SECONDS));
            MunicipalRequest stored = repository.findById(request.getId()).orElseThrow();
            assertEquals("oid-bruno", stored.getAssigneeId());
            assertEquals(RequestStatus.INGRESADO, stored.getStatus());
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MunicipalRequest save(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino-1");
        request.assignFolio(TestFolios.unique());
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private static List<String> ids(List<MunicipalRequest> requests) {
        return requests.stream().map(MunicipalRequest::getId).toList();
    }

    /** Corre la operación y devuelve "200" o el código y el motivo del error. */
    private static Callable<String> statusOf(Runnable operation) {
        return () -> {
            try {
                operation.run();
                return "200";
            } catch (ResponseStatusException ex) {
                return ex.getStatusCode().value() + " " + ex.getReason();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
