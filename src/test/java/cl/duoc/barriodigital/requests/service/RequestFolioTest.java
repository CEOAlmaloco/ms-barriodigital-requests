package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.domain.ActivityKind;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestActivity;
import cl.duoc.barriodigital.requests.messaging.DomainEventRecorder;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.persistence.FolioCounterRepository;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import cl.duoc.barriodigital.requests.persistence.RequestActivityRepository;
import cl.duoc.barriodigital.requests.web.CallerContext;
import cl.duoc.barriodigital.requests.web.dto.CreateRequestDto;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folio contra JPA real en H2 (contrato EP3-05, sección 1). No valida el entorno Oracle desplegado.
 * Cada test usa un año propio, porque el contador es compartido dentro del contexto.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RequestService.class, FolioCounterService.class, RequestCreationOrchestrationService.class,
        RequestFolioTest.FixedClock.class, DomainEventRecorder.class, NotificationPreferenceService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class RequestFolioTest {

    @Autowired RequestCreationOrchestrationService creation;
    @Autowired RequestService requests;
    @Autowired MunicipalRequestRepository repository;
    @Autowired FolioCounterRepository counters;
    @Autowired FolioCounterService folioCounters;
    @Autowired RequestActivityRepository activity;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    @MockBean EventPublisher publisher;

    private final CallerContext vecino = new CallerContext("oid-1", "Vecino", "Vecino");

    @Test
    void dosAltasSeguidasRecibenNumerosCorrelativos() {
        clock.set(Instant.parse("2030-05-10T15:00:00Z"));

        MunicipalRequest first = create();
        MunicipalRequest second = create();

        assertEquals("BD-2030-000001", first.getFolio());
        assertEquals("BD-2030-000002", second.getFolio());
        assertEquals(2, counters.findById(2030).orElseThrow().getLastNumber());
    }

    @Test
    void elAltaDel31DeDiciembreEnChileEsDelAnioQueTerminaYUsaUnSoloInstante() {
        // 31-dic-2031 a las 23:30 en Chile; en UTC ya es 1-ene-2032.
        Instant newYearEve = Instant.parse("2032-01-01T02:30:00Z");
        clock.set(newYearEve);

        MunicipalRequest created = create();

        assertEquals("BD-2031-000001", created.getFolio());
        MunicipalRequest stored = repository.findById(created.getId()).orElseThrow();
        assertEquals(newYearEve, stored.getCreatedAt());
        assertEquals(newYearEve, stored.getUpdatedAt());
        List<RequestActivity> marks = activity.findByRequestIdOrderByOccurredAtAsc(created.getId());
        assertEquals(ActivityKind.CREADO, marks.get(0).getKind());
        assertEquals(newYearEve, marks.get(0).getOccurredAt());
        assertTrue(counters.findById(2032).isEmpty());
    }

    @Test
    void elFolioEsUnico() {
        clock.set(Instant.parse("2033-03-01T15:00:00Z"));
        MunicipalRequest created = create();

        MunicipalRequest duplicate = new MunicipalRequest("t", "d", "bache", "dir", "vecino");
        duplicate.assignFolio(created.getFolio());

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
    }

    @Test
    void elFolioNoCambiaAunqueSeModifiqueElTramite() {
        clock.set(Instant.parse("2034-03-01T15:00:00Z"));
        MunicipalRequest created = create();
        String original = created.getFolio();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            MunicipalRequest loaded = repository.findById(created.getId()).orElseThrow();
            ReflectionTestUtils.setField(loaded, "folio", "BD-2034-999999");
            ReflectionTestUtils.setField(loaded, "description", "descripcion nueva");
        });

        MunicipalRequest reloaded = repository.findById(created.getId()).orElseThrow();
        assertEquals("descripcion nueva", reloaded.getDescription());
        assertEquals(original, reloaded.getFolio());
    }

    @Test
    void unAltaRevertidaDevuelveSuNumero() {
        Instant now = Instant.parse("2035-03-01T15:00:00Z");
        clock.set(now);
        folioCounters.ensureYear(2035);

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            requests.create(body(), vecino, null, now);
            tx.setRollbackOnly();
        });

        assertEquals(0, counters.findById(2035).orElseThrow().getLastNumber());
        assertEquals("BD-2035-000001", create().getFolio());
    }

    @Test
    void sinContadorDelAnioElAltaFallaYNoGuardaNada() {
        Instant now = Instant.parse("2036-03-01T15:00:00Z");
        long before = repository.count();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> requests.create(body(), vecino, null, now));

        assertEquals("Falta el contador de folio del año 2036", error.getMessage());
        assertEquals(before, repository.count());
    }

    @RepeatedTest(5)
    void dosAltasSimultaneasEnElPrimerTramiteDelAnioTerminanConFoliosDistintos(RepetitionInfo repetition)
            throws Exception {
        int year = 2040 + repetition.getCurrentRepetition();
        clock.set(Instant.parse(year + "-06-01T15:00:00Z"));
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> alta = () -> {
            start.await();
            return create().getFolio();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(alta);
            Future<String> b = pool.submit(alta);
            start.countDown();

            Set<String> folios = Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(Set.of("BD-" + year + "-000001", "BD-" + year + "-000002"), folios);
            assertEquals(2, counters.findById(year).orElseThrow().getLastNumber());
        } finally {
            pool.shutdownNow();
        }
    }

    private MunicipalRequest create() {
        return creation.create(body(), vecino, null);
    }

    private static CreateRequestDto body() {
        return new CreateRequestDto("Hueco", "bache", "Calle 1");
    }

    /** Reloj que cada test fija en su propio año. */
    static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2030-01-01T15:00:00Z");

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        MutableClock clock() {
            return new MutableClock();
        }
    }
}
