package cl.duoc.barriodigital.requests.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Exige exactamente uno de los cuatro roles antes de cualquier caso de uso (contrato EP3-04, secciones 1 y 4).
 * Sin rol responde 403 ROLE_REQUIRED y con más de uno, 403 SINGLE_ROLE_REQUIRED.
 * Es la segunda capa: el BFF aplica la misma regla. /api/requests/meta/** queda fuera porque el BFF la llama
 * sin identidad y solo entrega datos fijos.
 */
@Component
public class SingleRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        new CallerContext(
                request.getHeader(CallerContext.HEADER_USER_ID),
                request.getHeader(CallerContext.HEADER_USER_ROLES)
        ).requireSingleRole();
        return true;
    }
}
