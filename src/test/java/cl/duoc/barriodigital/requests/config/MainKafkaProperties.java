package cl.duoc.barriodigital.requests.config;

import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Lee el application.yml de src/main, que el de src/test oculta en el classpath. */
public final class MainKafkaProperties {

    private MainKafkaProperties() {
    }

    public static StandardEnvironment environment() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("main-application", new FileSystemResource("src/main/resources/application.yml"));
            StandardEnvironment environment = new StandardEnvironment();
            sources.forEach(environment.getPropertySources()::addFirst);
            return environment;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static KafkaProperties kafka() {
        return Binder.get(environment()).bind("spring.kafka", KafkaProperties.class).get();
    }

    public static Map<String, Object> producer() {
        return kafka().buildProducerProperties(null);
    }
}
