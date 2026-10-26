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
 * Publica eventos en RabbitMQ.
 * Quien llama indica el tipo; este componente elige exchange y routing key. La publicación corre en otro hilo: si RabbitMQ está caído se pierde el aviso, no el trámite, y no hay outbox.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate template;
    private final RabbitNamesProperties names;
    private final ObjectMapper mapper;
    private final Executor executor;

    /**
     * Deja listo el envío.
     * mandatory obliga a avisar si ninguna cola recibe el mensaje. Los callbacks anotan si el broker lo devuelve o no lo confirma.
     */
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

    /** Publica con un traceId nuevo. Quien llama no espera a RabbitMQ. */
    public void publish(String type, String correlationId, Object payload) {
        publish(type, correlationId, payload, UUID.randomUUID());
    }

    /**
     * Encola el envío en otro hilo.
     * Si ese hilo no acepta el trabajo, lo registra y la respuesta HTTP sigue. El mismo traceId agrupa los eventos de un cambio de estado.
     */
    public void publish(String type, String correlationId, Object payload, UUID traceId) {
        try {
            executor.execute(() -> sendQuietly(type, correlationId, payload, traceId));
        } catch (RuntimeException ex) {
            log.error("No se pudo encolar la publicacion type={} correlationId={}", type, correlationId, ex);
        }
    }

    /**
     * Arma el evento, elige exchange y routing key, y lo manda persistente.
     * Si falla, solo lo registra: no devuelve la excepción a quien cambió el trámite.
     */
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

    /**
     * crew.ticket va al exchange cmd.direct con esa routing key.
     * El resto va al exchange cmd.topic y la routing key es el propio type.
     */
    private Destination destination(String type) {
        if (type != null && type.equals(names.routing().crewTicket())) {
            return new Destination(names.exchange().direct(), names.routing().crewTicket());
        }
        return new Destination(names.exchange().topic(), type);
    }

    /** El broker devolvió el mensaje porque ninguna cola tenía binding para esa routing key. */
    void onReturned(ReturnedMessage returned) {
        log.error(
                "mensaje devuelto exchange={} routingKey={} eventId={} reply={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                eventId(returned.getMessage()),
                returned.getReplyText());
    }

    /** Si el broker no confirmó que guardó el mensaje, lo registra. Un ack verdadero no escribe nada. */
    void onConfirm(CorrelationData correlation, boolean ack, String cause) {
        if (ack) {
            return;
        }
        String eventId = correlation == null || correlation.getId() == null ? "desconocido" : correlation.getId();
        log.error("el broker no confirmo el mensaje eventId={} causa={}", eventId, cause);
    }

    /** Lee el eventId del JSON devuelto. Si no puede, escribe "desconocido". */
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
