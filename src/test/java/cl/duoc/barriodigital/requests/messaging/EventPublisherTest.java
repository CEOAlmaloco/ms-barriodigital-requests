package cl.duoc.barriodigital.requests.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cl.duoc.barriodigital.requests.config.RabbitNamesProperties;
import cl.duoc.barriodigital.requests.config.RabbitNamesProperties.Exchange;
import cl.duoc.barriodigital.requests.config.RabbitNamesProperties.Queue;
import cl.duoc.barriodigital.requests.config.RabbitNamesProperties.Routing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class EventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final RabbitNamesProperties names = new RabbitNamesProperties(
            new Exchange("cmd.topic", "cmd.direct", "cmd.dead.dlx"),
            new Queue("q.cmd.email", "q.cmd.email.dlq", "q.cmd.crew", "q.cmd.crew.dlq",
                    "q.cmd.certificate", "q.cmd.certificate.dlq"),
            new Routing("email.send", "email.*", "crew.ticket", "crew.#",
                    "certificate.gen", "certificate.*", "email.dead", "crew.dead", "certificate.dead"));

    @ParameterizedTest
    @ValueSource(strings = {"email.admitted", "email.resolved", "email.visit", "crew.ticket",
            "certificate.receipt", "certificate.resolved"})
    void elJsonTraeElSobreYElMensajeEsPersistente(String type) throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);

        publisher.publish(type, "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                new RequestEventPayload("7b6e1d44-2a18-4c90-9f01-88aa11bb22cc", "bache", null,
                        "Los Aromos 120", null));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(
                eq(type.equals("crew.ticket") ? "cmd.direct" : "cmd.topic"),
                org.mockito.ArgumentMatchers.eq(type),
                body.capture(),
                processor.capture(),
                any(CorrelationData.class));

        Message message = new Jackson2JsonMessageConverter(mapper)
                .toMessage(body.getValue(), new MessageProperties());
        message = processor.getValue().postProcessMessage(message);
        JsonNode json = mapper.readTree(message.getBody());

        assertEquals(type, json.get("type").asText());
        assertTrue(json.get("eventId").asText().length() > 10);
        assertTrue(json.get("timestamp").asText().endsWith("Z"));
        assertTrue(json.get("traceId").asText().length() > 10);
        assertEquals("7b6e1d44-2a18-4c90-9f01-88aa11bb22cc", json.get("correlationId").asText());
        assertEquals("Los Aromos 120", json.get("payload").get("address").asText());
        assertEquals(5, json.get("payload").size());
        assertTrue(json.get("payload").get("procedureLabel").isNull());
        assertTrue(json.get("payload").get("solicitanteEmail").isNull());
        UUID.fromString(json.get("eventId").asText());
        UUID.fromString(json.get("traceId").asText());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
    }

    @Test
    void cadaEventoTieneIdentidadPropia() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);
        publisher.publish("email.admitted", "tramite", Map.of());
        publisher.publish("email.resolved", "tramite", Map.of());
        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<CorrelationData> correlations = ArgumentCaptor.forClass(CorrelationData.class);
        verify(template, times(2)).convertAndSend(anyString(), anyString(), bodies.capture(),
                any(MessagePostProcessor.class), correlations.capture());
        EventEnvelope first = (EventEnvelope) bodies.getAllValues().get(0);
        EventEnvelope second = (EventEnvelope) bodies.getAllValues().get(1);
        assertNotEquals(first.eventId(), second.eventId());
        assertNotEquals(first.traceId(), second.traceId());
        assertEquals(first.eventId().toString(), correlations.getAllValues().get(0).getId());
        assertEquals(second.eventId().toString(), correlations.getAllValues().get(1).getId());
    }

    @ParameterizedTest
    @CsvSource({"crew.ticket,email.visit,visita.direct", "email.resolved,certificate.resolved,visita.topic"})
    void elParComparteTrazaYSnapshotConDestinosConfiguradosEIdentidadPropia(String firstType, String secondType, String firstExchange) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        var configuredNames = new RabbitNamesProperties(
                new Exchange("visita.topic", "visita.direct", "visita.dead"), names.queue(), names.routing());
        List<Runnable> tasks = new ArrayList<>();
        EventPublisher publisher = new EventPublisher(template, configuredNames, mapper, tasks::add);
        UUID trace = UUID.randomUUID();
        var payload = new RequestEventPayload("tramite", "tipo_nuevo", "Etiqueta guardada", "Direccion guardada", null);
        publisher.publish(firstType, "tramite", payload, trace);
        publisher.publish(secondType, "tramite", payload, trace);
        assertEquals(2, tasks.size());
        verify(template, times(0)).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        tasks.forEach(Runnable::run);
        ArgumentCaptor<Object> crew = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> email = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSend(eq(firstExchange), eq(firstType), crew.capture(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(template).convertAndSend(eq("visita.topic"), eq(secondType), email.capture(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        EventEnvelope first = (EventEnvelope) crew.getValue();
        EventEnvelope second = (EventEnvelope) email.getValue();
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(trace, first.traceId());
        assertEquals(trace, second.traceId());
        assertEquals("tramite", first.correlationId());
        assertEquals(first.correlationId(), second.correlationId());
        assertSame(payload, first.payload());
        assertSame(payload, second.payload());
    }

    @ParameterizedTest
    @CsvSource({"crew.ticket,email.visit,cmd.direct", "email.resolved,certificate.resolved,cmd.topic"})
    void falloDelPrimerEnvioNoImpideIntentarElSegundo(String firstType, String secondType, String firstExchange) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new ConnectException("fallo controlado")))
                .when(template).convertAndSend(eq(firstExchange), eq(firstType), any(),
                        any(MessagePostProcessor.class), any(CorrelationData.class));
        List<Runnable> tasks = new ArrayList<>();
        EventPublisher publisher = new EventPublisher(template, names, mapper, tasks::add);
        UUID trace = UUID.randomUUID();
        publisher.publish(firstType, "tramite", Map.of(), trace);
        publisher.publish(secondType, "tramite", Map.of(), trace);
        assertEquals(2, tasks.size());
        tasks.forEach(Runnable::run);
        verify(template).convertAndSend(eq(firstExchange), eq(firstType), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        verify(template).convertAndSend(eq("cmd.topic"), eq(secondType), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @ParameterizedTest
    @CsvSource({"crew.ticket,email.visit,false", "crew.ticket,email.visit,true",
            "email.resolved,certificate.resolved,false", "email.resolved,certificate.resolved,true"})
    void rechazoDeLaPrimeraTareaNoSuprimeElSegundoIntentoDeEncolado(String firstType, String secondType, boolean rejectBoth) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        AtomicInteger submissions = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        EventPublisher publisher = new EventPublisher(template, names, mapper, task -> {
            if (submissions.getAndIncrement() == 0 || rejectBoth) throw new RejectedExecutionException("saturado");
            tasks.add(task);
        });
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            UUID trace = UUID.randomUUID();
            publisher.publish(firstType, "tramite", Map.of(), trace);
            publisher.publish(secondType, "tramite", Map.of(), trace);
            assertEquals(2, submissions.get());
            assertEquals(rejectBoth ? 0 : 1, tasks.size());
            assertEquals(rejectBoth ? 2 : 1, logs.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("No se pudo encolar la publicacion")).count());
            tasks.forEach(Runnable::run);
            verify(template, times(rejectBoth ? 0 : 1)).convertAndSend(eq("cmd.topic"), eq(secondType), any(),
                    any(MessagePostProcessor.class), any(CorrelationData.class));
        } finally {
            ((Logger) LoggerFactory.getLogger(EventPublisher.class)).detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void crewTicketSalePorElExchangeDirect() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);

        publisher.publish("crew.ticket", "tramite-1", Map.of("address", "Los Aromos 120"));

        verify(template).convertAndSend(
                org.mockito.ArgumentMatchers.eq("cmd.direct"),
                org.mockito.ArgumentMatchers.eq("crew.ticket"),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class));
    }

    @Test
    void siElBrokerNoRespondeNoLanzaYVuelveEnMenosDeUnSegundo() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenAnswer(invocation -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("no se libero la conexion");
            } finally {
                finished.countDown();
            }
            throw new AmqpConnectException(new ConnectException("broker inalcanzable"));
        });
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMessageConverter(new Jackson2JsonMessageConverter(mapper));
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-publisher-test");
            thread.setDaemon(true);
            return thread;
        });
        EventPublisher publisher = new EventPublisher(template, names, mapper, executor);

        try {
            long start = System.nanoTime();
            assertDoesNotThrow(() -> publisher.publish("email.admitted", "tramite-1", Map.of()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 1_000, "el publicador tardo " + elapsedMs + " ms");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, finished.getCount(), "el llamador debe volver con la conexion aun bloqueada");
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unMensajeDevueltoQuedaEnElLog() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);
        ListAppender<ILoggingEvent> logs = attachLogs();

        Message message = MessageBuilder.withBody(
                "{\"eventId\":\"8f1c2a40-6b2e-4d1a-9c33-1a0e5f7b2d10\"}".getBytes(StandardCharsets.UTF_8)).build();
        publisher.onReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "cmd.topic", "email.admitted"));

        assertTrue(logs.list.stream().anyMatch(event ->
                event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("exchange=cmd.topic")
                        && event.getFormattedMessage().contains("routingKey=email.admitted")
                        && event.getFormattedMessage().contains("eventId=8f1c2a40-6b2e-4d1a-9c33-1a0e5f7b2d10")));
    }

    @Test
    void siElEnvioFallaNoLanzaAlQueLlamo() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new ConnectException("no responde")))
                .when(template)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);

        assertDoesNotThrow(() -> publisher.publish("email.resolved", "tramite-1", Map.of()));
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(EventPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
