package cl.duoc.barriodigital.requests.config;

import cl.duoc.barriodigital.requests.messaging.OutboxRelay;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaConfigTest {

    @Test
    void elApplicationYmlDeMainCumpleElContrato() {
        KafkaProperties kafka = MainKafkaProperties.kafka();
        Map<String, Object> producer = kafka.buildProducerProperties(null);

        assertEquals("all", String.valueOf(producer.get(ProducerConfig.ACKS_CONFIG)));
        assertEquals("true", String.valueOf(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)));
        assertEquals("5", String.valueOf(producer.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)));
        assertEquals("5000", String.valueOf(producer.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)));
        assertEquals("5000", String.valueOf(producer.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)));
        assertEquals("10000", String.valueOf(producer.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)));
        assertEquals(StringSerializer.class, producer.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class, producer.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertFalse(kafka.getAdmin().isAutoCreate(), "requests no crea topicos: solo kafka-admin");
        assertDoesNotThrow(() -> KafkaConfig.checkProducerLimits(producer, OutboxRelay.ACK_TIMEOUT));
    }

    @Test
    void elApplicationYmlDeMainDefineElTopicoYElRelay() {
        Binder binder = Binder.get(MainKafkaProperties.environment());
        assertEquals("requests.events",
                binder.bind("barriodigital.kafka.topic.requests-events", String.class).get());
        assertEquals(1000, binder.bind("barriodigital.outbox.relay-delay-ms", Integer.class).get());
        assertTrue(binder.bind("barriodigital.outbox.scheduling.enabled", Boolean.class).get());
        assertEquals(2, binder.bind("spring.task.scheduling.pool.size", Integer.class).get());
        assertEquals("always", binder.bind("management.endpoint.health.show-details", String.class).get());
    }

    @Test
    void laEsperaDelRelayEsDeDiezSegundos() {
        assertEquals(Duration.ofSeconds(10), OutboxRelay.ACK_TIMEOUT);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "delivery.timeout.ms, 15000",
            "max.block.ms, 60000",
            "request.timeout.ms, 11000",
            "acks, 1",
            "enable.idempotence, false"
    })
    void unLimiteDescoordinadoImpideArrancar(String key, String value) {
        Map<String, Object> producer = new HashMap<>(MainKafkaProperties.producer());
        producer.put(key, value);
        assertThrows(IllegalStateException.class,
                () -> KafkaConfig.checkProducerLimits(producer, OutboxRelay.ACK_TIMEOUT));
    }

    @Test
    void unLimiteSinDefinirImpideArrancar() {
        for (String key : new String[]{ProducerConfig.MAX_BLOCK_MS_CONFIG, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, ProducerConfig.LINGER_MS_CONFIG}) {
            Map<String, Object> producer = new HashMap<>(MainKafkaProperties.producer());
            producer.remove(key);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> KafkaConfig.checkProducerLimits(producer, OutboxRelay.ACK_TIMEOUT));
            assertTrue(ex.getMessage().contains(key));
        }
    }
}
