package cl.duoc.barriodigital.requests.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Aplica ORACLE_WALLET_DIR como TNS_ADMIN antes de crear el DataSource (EP1-21).
 */
@Configuration
public class OracleWalletConfiguration {

    @Bean
    static BeanFactoryPostProcessor oracleWalletTnsAdmin() {
        return new OracleWalletTnsAdminPostProcessor();
    }

    static final class OracleWalletTnsAdminPostProcessor
            implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            String walletDir = environment.getProperty("ORACLE_WALLET_DIR");
            if (!StringUtils.hasText(walletDir)) {
                return;
            }
            Path path = Path.of(walletDir.trim());
            if (!Files.isDirectory(path)) {
                throw new IllegalStateException(
                        "ORACLE_WALLET_DIR no existe o no es una carpeta: " + path
                                + ". Descomprimí el wallet de Autonomous ahí (tiene que haber tnsnames.ora)."
                );
            }
            if (!Files.isRegularFile(path.resolve("tnsnames.ora"))) {
                throw new IllegalStateException(
                        "En ORACLE_WALLET_DIR falta tnsnames.ora: " + path
                                + ". Revisá que hayas descomprimido el ZIP completo del wallet."
                );
            }
            System.setProperty("oracle.net.tns_admin", path.toAbsolutePath().toString());
        }
    }
}
