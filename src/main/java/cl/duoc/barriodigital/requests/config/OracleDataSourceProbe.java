package cl.duoc.barriodigital.requests.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Confirma al arrancar que el pool Oracle responde (EP1-21).
 * No imprime URL completa ni passwords.
 */
@Component
public class OracleDataSourceProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OracleDataSourceProbe.class);

    private final DataSource dataSource;

    public OracleDataSourceProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            log.info("Oracle OK: pool conectado ({} {})", product, version);
        }
    }
}
