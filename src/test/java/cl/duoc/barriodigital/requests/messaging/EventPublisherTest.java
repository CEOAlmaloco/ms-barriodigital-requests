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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void elJsonTraeElSobreYElMensajeEsPersistente() throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        EventPublisher publisher = new EventPublisher(template, names, mapper, Runnable::run);

        publisher.publish("email.admitted", "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc",
                Map.of("requestId", "7b6e1d44-2a18-4c90-9f01-88aa11bb22cc", "address", "Los Aromos 120"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(
                org.mockito.ArgumentMatchers.eq("cmd.topic"),
                org.mockito.ArgumentMatchers.eq("email.admitted"),
                body.capture(),
                processor.capture(),
                any(CorrelationData.class));

        Message message = new Jackson2JsonMessageConverter(mapper)
                .toMessage(body.getValue(), new MessageProperties());
        message = processor.getValue().postProcessMessage(message);
        JsonNode json = mapper.readTree(message.getBody());

        assertEquals("email.admitted", json.get("type").asText());
        assertTrue(json.get("eventId").asText().length() > 10);
        assertTrue(json.get("timestamp").asText().endsWith("Z"));
        assertTrue(json.get("traceId").asText().length() > 10);
        assertEquals("7b6e1d44-2a18-4c90-9f01-88aa11bb22cc", json.get("correlationId").asText());
        assertEquals("Los Aromos 120", json.get("payload").get("address").asText());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
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
        AtomicBoolean blockedInConnect = new AtomicBoolean(false);
        AtomicBoolean connectFinished = new AtomicBoolean(false);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.createConnection()).thenAnswer(invocation -> {
            blockedInConnect.set(true);
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            connectFinished.set(true);
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

        long start = System.nanoTime();
        assertDoesNotThrow(() -> publisher.publish("email.admitted", "tramite-1", Map.of()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 1_000, "el publicador tardo " + elapsedMs + " ms");
        Thread.sleep(300);
        assertTrue(blockedInConnect.get(), "el hilo de la peticion no debio esperar, pero el worker tampoco llego a conectar");
        assertFalse(connectFinished.get(), "la conexion de 5 s no debio terminar antes de que volviera quien llamo");
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
