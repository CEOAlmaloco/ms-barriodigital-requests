package cl.duoc.barriodigital.requests.config;

import cl.duoc.barriodigital.requests.web.SingleRoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptores de la API. Los health de actuator no pasan por aquí. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Rutas de datos fijos que el BFF llama sin identidad (EP3-04, sección 4). */
    static final String META = "/api/requests/meta/**";

    private final SingleRoleInterceptor singleRole;

    public WebConfig(SingleRoleInterceptor singleRole) {
        this.singleRole = singleRole;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(singleRole).addPathPatterns("/api/**").excludePathPatterns(META);
    }
}
