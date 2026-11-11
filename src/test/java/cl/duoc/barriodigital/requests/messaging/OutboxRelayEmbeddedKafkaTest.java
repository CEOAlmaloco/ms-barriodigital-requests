package cl.duoc.barriodigital.requests.messaging;

import cl.duoc.barriodigital.requests.config.MainKafkaProperties;
import cl.duoc.barriodigital.requests.domain.OutboxEvent;
import cl.duoc.barriodigital.requests.persistence.OutboxEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EmbeddedKafka(partitions = 3, topics = OutboxRelayEmbeddedKafkaTest.TOPIC)
class OutboxRelayEmbeddedKafkaTest {

    static final String TOPIC = "requests.events";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REQUESTS = List.of(
            "aaaaaaaa-1111-4000-8000-000000000001",
            "bbbbbbbb-2222-4000-8000-000000000002",
            "cccccccc-3333-4000-8000-000000000003");

    @Test
    void cadaTramiteVaAUnaParticionEnOrdenDeSequence() throws Exception {
        EmbeddedKafkaBroker broker = EmbeddedKafkaCondition.getBroker();
        Map<String, Object> producer = new HashMap<>(MainKafkaProperties.producer());
        producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(producer);
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(factory);

        List<OutboxEvent> events = new ArrayList<>();
        for (long sequence = 1; sequence <= 9; sequence++) {
            events.add(OutboxFixtures.stored(sequence, REQUESTS.get((int) ((sequence - 1) % REQUESTS.size()))));
        }
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        when(outbox.findFirst100ByPublishedAtIsNullOrderByEventSequenceAsc()).thenReturn(events);
        when(outbox.markPublished(any(), any())).thenReturn(1);

        try {
            new OutboxRelay(outbox, kafka, Clock.systemUTC(), TOPIC, OutboxRelay.ACK_TIMEOUT).publishPending();
        } finally {
            factory.destroy();
        }

        InOrder marks = inOrder(outbox);
        for (OutboxEvent event : events) {
            marks.verify(outbox).markPublished(eq(event.getEventId()), any());
        }

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("ep3-32-verificacion", "false", broker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(consumerProps, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (received.size() < events.size() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
            }
        }
        assertEquals(events.size(), received.size());

        Map<String, Integer> partitionByRequest = new HashMap<>();
        Map<Integer, Long> lastSequenceByPartition = new HashMap<>();
        for (ConsumerRecord<String, String> record : received) {
            JsonNode envelope = JSON.readTree(record.value());
            String requestId = envelope.at("/payload/requestId").asText();
            long sequence = envelope.get("sequence").asLong();
            assertEquals(requestId, record.key());
            assertFalse(record.headers().iterator().hasNext(), "sin headers de tipo Java");
            Integer previous = partitionByRequest.putIfAbsent(requestId, record.partition());
            if (previous != null) {
                assertEquals(previous, record.partition(), "un tramite cambio de particion");
            }
            Long last = lastSequenceByPartition.put(record.partition(), sequence);
            if (last != null) {
                assertTrue(sequence > last, "sequence " + sequence + " llego despues de " + last);
            }
            OutboxEvent source = events.get((int) sequence - 1);
            assertEquals(source.getEventId(), envelope.get("eventId").asText());
        }
        assertEquals(REQUESTS.size(), partitionByRequest.size());
    }
}
