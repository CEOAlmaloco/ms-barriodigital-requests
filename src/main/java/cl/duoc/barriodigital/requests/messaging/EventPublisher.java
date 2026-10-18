package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.config.RabbitConfig;
import cl.duoc.barriodigital.requests.config.RabbitNamesProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Los servicios de negocio llaman a {@link #publish}. No nombran exchanges ni routing keys.
 * La publicación corre en otro hilo: si el broker no responde, quien llamó no espera ni recibe la excepción.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate template;
    private final RabbitNamesProperties names;
    private final ObjectMapper mapper;
    private final Executor executor;

    public EventPublisher(
            RabbitTemplate template,
            RabbitNamesProperties names,
            ObjectMapper mapper,
            @Qualifier(RabbitConfig.PUBLISHER_EXECUTOR) Executor executor) {
        this.template = template;
        this.names = names;
        this.mapper = mapper;
        this.executor = executor;
        this.template.setMandatory(true);
        this.template.setReturnsCallback(this::onReturned);
        this.template.setConfirmCallback(this::onConfirm);
    }

    public void publish(String type, String correlationId, Object payload) {
        publish(type, correlationId, payload, UUID.randomUUID());
    }

    public void publish(String type, String correlationId, Object payload, UUID traceId) {
        try {
            executor.execute(() -> sendQuietly(type, correlationId, payload, traceId));
        } catch (RuntimeException ex) {
            log.error("No se pudo encolar la publicacion type={} correlationId={}", type, correlationId, ex);
        }
    }

    private void sendQuietly(String type, String correlationId, Object payload, UUID traceId) {
        try {
            EventEnvelope envelope = new EventEnvelope(
                    type,
                    UUID.randomUUID(),
                    Instant.now(),
                    traceId,
                    correlationId,
                    payload);
            Destination destination = destination(type);
            CorrelationData correlation = new CorrelationData(envelope.eventId().toString());
            template.convertAndSend(destination.exchange(), destination.routingKey(), envelope, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, correlation);
        } catch (RuntimeException ex) {
            log.error("No se pudo publicar type={} correlationId={}", type, correlationId, ex);
        }
    }

    private Destination destination(String type) {
        if (type != null && type.equals(names.routing().crewTicket())) {
            return new Destination(names.exchange().direct(), names.routing().crewTicket());
        }
        return new Destination(names.exchange().topic(), type);
    }

    void onReturned(ReturnedMessage returned) {
        log.error(
                "mensaje devuelto exchange={} routingKey={} eventId={} reply={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                eventId(returned.getMessage()),
                returned.getReplyText());
    }

    void onConfirm(CorrelationData correlation, boolean ack, String cause) {
        if (ack) {
            return;
        }
        String eventId = correlation == null || correlation.getId() == null ? "desconocido" : correlation.getId();
        log.error("el broker no confirmo el mensaje eventId={} causa={}", eventId, cause);
    }

    private String eventId(Message message) {
        if (message == null || message.getBody() == null) {
            return "desconocido";
        }
        try {
            JsonNode node = mapper.readTree(message.getBody());
            JsonNode id = node.get("eventId");
            if (id == null || id.isNull() || id.asText().isBlank()) {
                return "desconocido";
            }
            return id.asText();
        } catch (Exception ex) {
            return "desconocido";
        }
    }

    private record Destination(String exchange, String routingKey) {
    }
}
