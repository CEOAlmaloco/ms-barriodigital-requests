package cl.duoc.barriodigital.requests.config;

import cl.duoc.barriodigital.requests.web.SingleRoleInterceptor;
import cl.duoc.barriodigital.requests.web.StaffMemberInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptores de la API. Los health de actuator no pasan por aquí. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Rutas de datos fijos que el BFF llama sin identidad (EP3-04, sección 4). */
    static final String META = "/api/requests/meta/**";

    private final SingleRoleInterceptor singleRole;
    private final StaffMemberInterceptor staffMember;

    public WebConfig(SingleRoleInterceptor singleRole, StaffMemberInterceptor staffMember) {
        this.singleRole = singleRole;
        this.staffMember = staffMember;
    }

    /** Primero la regla de un rol; después el registro del personal, que solo ve llamadas ya aceptadas. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(singleRole).addPathPatterns("/api/**").excludePathPatterns(META);
        registry.addInterceptor(staffMember).addPathPatterns("/api/**").excludePathPatterns(META);
    }
}
