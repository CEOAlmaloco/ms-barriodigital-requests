package cl.duoc.barriodigital.requests.config;

import cl.duoc.barriodigital.requests.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = RequestsStartsWithoutBrokerTest.Slice.class,
        properties = "spring.rabbitmq.addresses=203.0.113.1:5672")
class RequestsStartsWithoutBrokerTest {

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private RabbitProperties rabbitProperties;

    @Autowired
    private TopicExchange topicExchange;

    @Autowired
    @Qualifier("directExchange")
    private DirectExchange directExchange;

    @Test
    void arrancaAunqueElBrokerNoResponde() {
        assertNotNull(publisher);
        assertEquals("cmd.topic", topicExchange.getName());
        assertEquals("cmd.direct", directExchange.getName());
        assertTrue(rabbitProperties.getConnectionTimeout().toMillis() <= 2_000);
        assertTrue(rabbitProperties.isPublisherReturns());
        assertTrue(Boolean.TRUE.equals(rabbitProperties.getTemplate().getMandatory()));
        assertTrue(rabbitProperties.getPublisherConfirmType() == CachingConnectionFactory.ConfirmType.CORRELATED);
    }

    @org.springframework.boot.SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class
    })
    @Import({RabbitConfig.class, EventPublisher.class})
    static class Slice {
    }
}
