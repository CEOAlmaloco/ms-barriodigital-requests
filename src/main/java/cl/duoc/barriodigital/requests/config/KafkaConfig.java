package cl.duoc.barriodigital.requests.config;

import cl.duoc.barriodigital.requests.messaging.OutboxRelay;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

import java.time.Duration;
import java.util.Map;

/** Productor de requests.events. El KafkaTemplate lo arma Spring Boot desde spring.kafka. */
@Configuration
@EnableConfigurationProperties(KafkaNamesProperties.class)
public class KafkaConfig {

    /** Reemplaza al listener de Spring Boot, que loguea el mensaje completo en cada envío fallido. */
    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {
        return new ProducerListener<>() {
        };
    }

    @Bean
    InitializingBean kafkaProducerLimitsCheck(KafkaProperties kafka) {
        return () -> checkProducerLimits(kafka.buildProducerProperties(null), OutboxRelay.ACK_TIMEOUT);
    }

    /** Los límites del productor deben caber en la espera del relay; si no, el servicio no arranca. */
    static void checkProducerLimits(Map<String, Object> producer, Duration ackTimeout) {
        long ackWait = ackTimeout.toMillis();
        if (!"all".equals(String.valueOf(producer.get(ProducerConfig.ACKS_CONFIG)))) {
            throw new IllegalStateException("spring.kafka.producer.acks debe ser all");
        }
        if (!"true".equals(String.valueOf(producer.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)))) {
            throw new IllegalStateException("enable.idempotence debe ser true");
        }
        long maxBlock = required(producer, ProducerConfig.MAX_BLOCK_MS_CONFIG);
        long requestTimeout = required(producer, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        long deliveryTimeout = required(producer, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        long linger = required(producer, ProducerConfig.LINGER_MS_CONFIG);
        if (maxBlock > ackWait) {
            throw new IllegalStateException("max.block.ms=" + maxBlock + " supera la espera del relay de "
                    + ackWait + " ms");
        }
        if (deliveryTimeout > ackWait) {
            throw new IllegalStateException("delivery.timeout.ms=" + deliveryTimeout
                    + " supera la espera del relay de " + ackWait + " ms");
        }
        if (deliveryTimeout < linger + requestTimeout) {
            throw new IllegalStateException("delivery.timeout.ms=" + deliveryTimeout
                    + " debe ser al menos linger.ms + request.timeout.ms (" + (linger + requestTimeout) + ")");
        }
    }

    private static long required(Map<String, Object> producer, String key) {
        Object value = producer.get(key);
        if (value == null) {
            throw new IllegalStateException("Falta " + key + " en spring.kafka.producer.properties");
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + " no es un numero: " + value, ex);
        }
    }
}
