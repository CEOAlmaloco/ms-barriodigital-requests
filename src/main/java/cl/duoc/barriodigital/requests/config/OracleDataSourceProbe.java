package cl.duoc.barriodigital.requests.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Al arrancar, abre una conexión y registra que Oracle respondió.
 * El log lleva producto y versión, sin clave ni URL.
 */
@Component
public class OracleDataSourceProbe implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OracleDataSourceProbe.class);

    private final DataSource dataSource;

    /** Recibe el DataSource del pool. */
    public OracleDataSourceProbe(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Abre una conexión y deja en el log que el pool respondió. */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            log.info("Oracle OK: pool conectado ({} {})", product, version);
        }
    }
}
