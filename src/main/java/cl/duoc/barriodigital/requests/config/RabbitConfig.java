package cl.duoc.barriodigital.requests.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Declara los exchanges de requests: cmd.topic, cmd.direct y cmd.dead.dlx.
 * Las colas las declara notify. La declaración ocurre al abrir la conexión, así que un broker caído no impide arrancar.
 */
@Configuration
@EnableConfigurationProperties(RabbitNamesProperties.class)
public class RabbitConfig {

    public static final String PUBLISHER_EXECUTOR = "eventPublisherExecutor";

    private final RabbitNamesProperties names;

    /** Recibe los nombres configurados. Con ellos se crean los exchanges. */
    public RabbitConfig(RabbitNamesProperties names) {
        this.names = names;
    }

    /** Exchange cmd.topic, durable. Por ahí salen los eventos que no son crew.ticket, como email y certificate. */
    @Bean
    TopicExchange topicExchange() {
        return ExchangeBuilder.topicExchange(names.exchange().topic()).durable(true).build();
    }

    /** Exchange cmd.direct, durable. crew.ticket sale por acá con esa routing key. */
    @Bean
    DirectExchange directExchange() {
        return ExchangeBuilder.directExchange(names.exchange().direct()).durable(true).build();
    }

    /** Exchange cmd.dead.dlx. Lo declara requests para que exista aunque notify todavía no arranque. */
    @Bean
    DirectExchange deadExchange() {
        return ExchangeBuilder.directExchange(names.exchange().dead()).durable(true).build();
    }

    /** Convierte el evento Java a JSON antes de publicarlo. */
    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    /**
     * Cliente que publica en RabbitMQ.
     * mandatory hace que el broker devuelva el mensaje si ninguna cola tiene binding para esa routing key.
     */
    @Bean
    RabbitTemplate rabbitTemplate(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }

    /**
     * Hilos que publican sin frenar la respuesta HTTP.
     * Hay 2 hilos fijos, puede crecer a 4, y la cola interna admite 200 publicaciones en espera.
     */
    @Bean(name = PUBLISHER_EXECUTOR)
    Executor eventPublisherExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("event-publisher-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }
}
