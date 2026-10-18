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
 * requests declara los exchanges. notify declara colas, DLQ y bindings.
 * {@code cmd.dead.dlx} también: el contrato dice que este servicio lo publica al arrancar.
 * La declaración ocurre al abrir la conexión, así que un broker caído no impide partir.
 */
@Configuration
@EnableConfigurationProperties(RabbitNamesProperties.class)
public class RabbitConfig {

    public static final String PUBLISHER_EXECUTOR = "eventPublisherExecutor";

    private final RabbitNamesProperties names;

    public RabbitConfig(RabbitNamesProperties names) {
        this.names = names;
    }

    /** cmd.topic: email.* y certificate.*. */
    @Bean
    TopicExchange topicExchange() {
        return ExchangeBuilder.topicExchange(names.exchange().topic()).durable(true).build();
    }

    /** cmd.direct: crew.ticket. */
    @Bean
    DirectExchange directExchange() {
        return ExchangeBuilder.directExchange(names.exchange().direct()).durable(true).build();
    }

    /** cmd.dead.dlx: lo declara requests para que exista aunque notify todavía no arranque. */
    @Bean
    DirectExchange deadExchange() {
        return ExchangeBuilder.directExchange(names.exchange().dead()).durable(true).build();
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    RabbitTemplate rabbitTemplate(
            org.springframework.amqp.rabbit.connection.ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }

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
