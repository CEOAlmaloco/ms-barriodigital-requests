package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import org.junit.jupiter.api.Test;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void dosAdmisionesDelMismoTramiteSoloPersistenUnaTransicion() throws Exception {
        MunicipalRequest created = repository.saveAndFlush(
                new MunicipalRequest("t", "d", "bache", "dir", "vecino"));
        String id = created.getId();
        CallerContext caller = new CallerContext("func-1", "Funcionario");
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                service.changeStatus(id, "ADMITIDO", null, caller);
                changed.countDown();
                await(commit);
                return 200;
            }));
            assertTrue(changed.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> {
                secondStarted.countDown();
                try {
                    service.changeStatus(id, "ADMITIDO", null, caller);
                    return 200;
                } catch (ResponseStatusException ex) {
                    assertEquals("No se puede pasar de ADMITIDO a ADMITIDO", ex.getReason());
                    return ex.getStatusCode().value();
                }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            // La primera transacción sigue abierta: la segunda no debe terminar aún.
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            commit.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS));
            assertEquals(409, second.get(5, TimeUnit.SECONDS));
            assertEquals(RequestStatus.ADMITIDO, repository.findById(id).orElseThrow().getStatus());
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
