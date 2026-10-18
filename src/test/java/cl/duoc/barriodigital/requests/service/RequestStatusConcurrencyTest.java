package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Transacciones reales en H2; no valida el entorno Oracle desplegado. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(RequestService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestStatusConcurrencyTest {

    @Autowired
    RequestService service;
    @Autowired
    MunicipalRequestRepository repository;
    @Autowired
    PlatformTransactionManager transactions;

    @MockBean
    EventPublisher publisher;

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket"})
    void dosIntentosDelMismoTramiteSoloPersistenYPublicanUnaTransicion(
            RequestStatus from, RequestStatus target, String type) throws Exception {
        MunicipalRequest initial = new MunicipalRequest("t", "d", "bache", "dir", "vecino");
        initial.setStatus(from);
        MunicipalRequest created = repository.saveAndFlush(initial);
        String id = created.getId();
        CallerContext caller = new CallerContext("func-1", "Funcionario");
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                service.changeStatus(id, target.name(), null, caller);
                changed.countDown();
                await(commit);
                return 200;
            }));
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                try {
                    service.changeStatus(id, target.name(), null, caller);
                    return 200;
                } catch (ResponseStatusException ex) {
                    assertEquals("No se puede pasar de " + target + " a " + target, ex.getReason());
                    return ex.getStatusCode().value();
                }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            // La primera transacción sigue abierta: la segunda no debe terminar aún.
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            verifyNoInteractions(publisher);
            commit.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(409, second.get(5, TimeUnit.SECONDS));
            assertEquals(target, repository.findById(id).orElseThrow().getStatus());
            // Son llamadas al publicador simulado, no recepciones verificadas en un broker.
            ArgumentCaptor<UUID> trace = ArgumentCaptor.forClass(UUID.class);
            RequestEventPayload payload = new RequestEventPayload(id, "bache", null, "dir", null);
            verify(publisher).publish(eq(type), eq(id), eq(payload), trace.capture());
            if (target == RequestStatus.EN_TERRENO) {
                verify(publisher).publish(eq("email.visit"), eq(id), eq(payload), eq(trace.getValue()));
            }
            verifyNoMoreInteractions(publisher);
        } finally {
            commit.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La prueba no liberó la transacción");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
