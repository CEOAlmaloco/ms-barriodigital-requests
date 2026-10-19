package cl.duoc.barriodigital.requests.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cl.duoc.barriodigital.requests.domain.MunicipalRequest;
import cl.duoc.barriodigital.requests.domain.RequestStatus;
import cl.duoc.barriodigital.requests.integration.RequestsH2Application;
import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import cl.duoc.barriodigital.requests.messaging.EventEnvelope;
import cl.duoc.barriodigital.requests.messaging.RequestEventPayload;
import cl.duoc.barriodigital.requests.persistence.MunicipalRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ConnectException;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** HTTP real, JPA/H2 real y ejecutor de producción; el transporte Rabbit se simula. */
@SpringBootTest(classes = RequestStatusEventsHttpTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:ep222-http;DB_CLOSE_DELAY=-1")
@ActiveProfiles("h2-integration")
class RequestStatusEventsHttpTest {

    @Autowired TestRestTemplate http;
    @Autowired MunicipalRequestRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @Autowired Jackson2JsonMessageConverter converter;
    @Autowired @Qualifier("eventPublisherExecutor") java.util.concurrent.Executor executor;
    @MockBean RabbitTemplate template;

    private final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
    private AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void prepare() {
        appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) { logs.add(event.getFormattedMessage()); }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(EventPublisher.class)).addAppender(appender);
        // Calienta dispatch/Jackson fuera de las peticiones medidas; no realiza transiciones.
        assertEquals(HttpStatus.OK, http.getForEntity("/api/requests/meta/transitions", String.class).getStatusCode());
    }

    @AfterEach
    void detachLogs() {
        ((Logger) LoggerFactory.getLogger(EventPublisher.class)).detachAppender(appender);
        appender.stop();
        Application.HTTP_THREAD.set(null);
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO,email.admitted", "EN_TERRENO,RESUELTO,email.resolved",
            "EN_GESTION,EN_TERRENO,crew.ticket", "INGRESADO,INGRESADO,certificate.receipt"})
    void httpTerminaAntesDeLiberarEnvioYElFalloPosteriorNoRevierte(
            RequestStatus from, RequestStatus target, String type) throws Exception {
        boolean creation = from == target;
        MunicipalRequest initial = creation ? null : create(from);
        int messages = target == RequestStatus.EN_TERRENO || target == RequestStatus.RESUELTO ? 2 : 1;
        CountDownLatch entered = new CountDownLatch(messages);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(messages);
        var workers = new ConcurrentLinkedQueue<Thread>();
        AtomicReference<RequestStatus> visible = new AtomicReference<>();
        var transactionsActive = new ConcurrentLinkedQueue<Boolean>();
        doAnswer(invocation -> {
            workers.add(Thread.currentThread());
            transactionsActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            EventEnvelope envelope = invocation.getArgument(2);
            visible.set(readStatus(envelope.correlationId()));
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("no se libero el envio");
                throw new AmqpConnectException(new ConnectException("broker detenido simulado"));
            } finally {
                exited.countDown();
            }
        }).when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));

        try {
            long start = System.nanoTime();
            ResponseEntity<String> response = creation ? post() : put(initial.getId(), target);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals(creation ? HttpStatus.CREATED : HttpStatus.OK, response.getStatusCode());
            assertTrue(millis < 2_000, "PUT tardo " + millis + " ms");
            assertTrue(entered.await(5, TimeUnit.SECONDS), "el worker no entro al envio");
            assertEquals(1, release.getCount(), "el HTTP debe terminar sin liberar el envio");
            assertEquals(messages, exited.getCount());
            assertNotNull(Application.HTTP_THREAD.get());
            assertEquals(messages, workers.size());
            workers.forEach(worker -> {
                assertNotSame(Application.HTTP_THREAD.get(), worker);
                assertTrue(worker.getName().startsWith("event-publisher-"));
            });
            assertTrue(transactionsActive.stream().allMatch(active -> !active));
            assertEquals(target, visible.get());
            assertEquals(target, readStatus(mapper.readTree(response.getBody()).get("id").asText()));
        } finally {
            release.countDown();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            drainWorkers();
        }
        assertTrue(logs.stream().anyMatch(line -> line.contains("No se pudo publicar type=" + type)));
        if (target == RequestStatus.EN_TERRENO) {
            assertTrue(logs.stream().anyMatch(line -> line.contains("No se pudo publicar type=email.visit")));
        }
        if (target == RequestStatus.RESUELTO) {
            assertTrue(logs.stream().anyMatch(line -> line.contains("No se pudo publicar type=certificate.resolved")));
        }
        if (!creation) assertEquals(target, readStatus(initial.getId()));
    }

    @ParameterizedTest
    @CsvSource({"INGRESADO,ADMITIDO", "EN_TERRENO,RESUELTO", "EN_GESTION,EN_TERRENO", "INGRESADO,INGRESADO"})
    void ejecutorSaturadoNoEnviaEnHttpYConservaRespuestaExitosa(RequestStatus from, RequestStatus target) throws Exception {
        boolean creation = from == target;
        MunicipalRequest initial = creation ? null : create(from);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workers = new CountDownLatch(4);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        int existingWorkers = Math.max(pool.getPoolSize(), pool.getCorePoolSize());
        CountDownLatch occupied = new CountDownLatch(existingWorkers);
        Runnable block = () -> {
            workers.countDown();
            occupied.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        };
        try {
            // Ocupa primero los workers existentes para no competir con su toma de tareas de la cola.
            for (int i = 0; i < existingWorkers; i++) executor.execute(block);
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 200; i++) executor.execute(block);
            for (int i = existingWorkers; i < 4; i++) executor.execute(block);
            assertTrue(workers.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            ResponseEntity<String> response = creation ? post() : put(initial.getId(), target);
            assertEquals(creation ? HttpStatus.CREATED : HttpStatus.OK, response.getStatusCode());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 2_000, "PUT tardo " + millis + " ms");
            assertEquals(target, readStatus(mapper.readTree(response.getBody()).get("id").asText()));
            verify(template, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
            assertEquals(target == RequestStatus.EN_TERRENO || target == RequestStatus.RESUELTO ? 2 : 1, logs.stream()
                    .filter(line -> line.contains("No se pudo encolar la publicacion")).count());
        } finally {
            release.countDown();
            drainWorkers();
        }
    }

    @Test
    void converterDeLaAplicacionIncluyeLosCincoCamposYNulos() throws Exception {
        var payload = new RequestEventPayload("id", "bache", null, "dir", null);
        var message = converter.toMessage(payload, new org.springframework.amqp.core.MessageProperties());
        var json = mapper.readTree(message.getBody());
        assertEquals(5, json.size());
        assertEquals("id", json.get("requestId").textValue());
        assertEquals("bache", json.get("procedureType").textValue());
        assertEquals("dir", json.get("address").textValue());
        assertTrue(json.get("procedureLabel").isNull());
        assertTrue(json.get("solicitanteEmail").isNull());
    }

    private MunicipalRequest create(RequestStatus status) {
        MunicipalRequest request = new MunicipalRequest("t", "d", "bache", "dir", "vecino");
        request.setStatus(status);
        return repository.saveAndFlush(request);
    }

    private ResponseEntity<String> post() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "vecino");
        headers.set("X-User-Roles", "Vecino");
        return http.postForEntity("/api/requests", new HttpEntity<>(
                Map.of("description", "d", "procedureType", "bache", "address", "dir"), headers), String.class);
    }

    private ResponseEntity<String> put(String id, RequestStatus target) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "func");
        headers.set("X-User-Roles", "Funcionario");
        return http.exchange("/api/requests/" + id + "/status", HttpMethod.PUT,
                new HttpEntity<>(Map.of("status", target.name()), headers), String.class);
    }

    private RequestStatus readStatus(String id) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> repository.findById(id).orElseThrow().getStatus());
    }

    private void drainWorkers() throws InterruptedException {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((pool.getActiveCount() != 0 || pool.getQueueSize() != 0) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, pool.getActiveCount());
        assertEquals(0, pool.getQueueSize());
    }

    @Configuration
    @Import(RequestsH2Application.class)
    static class Application {
        static final AtomicReference<Thread> HTTP_THREAD = new AtomicReference<>();
        @Bean Filter requestThreadRecorder() {
            return (request, response, chain) -> {
                HTTP_THREAD.set(Thread.currentThread());
                chain.doFilter(request, response);
            };
        }
    }
}
