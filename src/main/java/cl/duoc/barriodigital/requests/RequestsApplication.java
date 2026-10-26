package cl.duoc.barriodigital.requests;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Arranca el servicio de trámites.
 */
@SpringBootApplication
public class RequestsApplication {

    /** Punto de entrada: levanta la aplicación Spring. */
    public static void main(String[] args) {
        SpringApplication.run(RequestsApplication.class, args);
    }
}
