package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.service.RequestService;
import cl.duoc.barriodigital.requests.service.StaffMemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registra al personal en staff_member en cada llamada de staff, antes del caso de uso (contrato EP3-05, sección 5.1).
 * Solo cuenta una llamada con X-User-Id y exactamente un rol de staff: el Vecino no se registra.
 * Un caché en memoria escribe a lo más una vez cada 15 minutos por oid, salvo que cambie el nombre o el rol.
 * Una llamada sin X-User-Name legible no cambia el nombre: StaffMemberService conserva el guardado.
 * Un fallo del registro no bloquea la llamada: se deja en el log y la llamada sigue.
 */
@Component
public class StaffMemberInterceptor implements HandlerInterceptor {

    static final Duration REFRESH = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(StaffMemberInterceptor.class);

    private final StaffMemberService staff;
    private final Clock clock;
    private final Map<String, Seen> lastSeen = new ConcurrentHashMap<>();

    public StaffMemberInterceptor(StaffMemberService staff, Clock clock) {
        this.staff = staff;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CallerContext caller = new CallerContext(
                request.getHeader(CallerContext.HEADER_USER_ID),
                request.getHeader(CallerContext.HEADER_USER_ROLES),
                request.getHeader(CallerContext.HEADER_USER_NAME)
        );
        if (caller.userId() == null || caller.userId().isBlank() || !caller.isStaff()) {
            return true;
        }
        String oid = caller.userId().trim();
        // El nombre llega codificado en URL (EP3-09) y se guarda decodificado, igual que actor_name.
        String name = RequestService.headerName(caller.userName());
        Seen previous = lastSeen.get(oid);
        Seen now = new Seen(name != null || previous == null ? name : previous.name(), caller.role(), clock.instant());
        if (previous != null && previous.sameAs(now) && now.at().isBefore(previous.at().plus(REFRESH))) {
            return true;
        }
        try {
            staff.touch(oid, name, now.role());
            lastSeen.put(oid, now);
        } catch (RuntimeException ex) {
            log.warn("No se pudo registrar al personal en staff_member error={}", ex.getClass().getSimpleName());
        }
        return true;
    }

    /** Lo último que se escribió de un oid. {@code name} es null si todavía no llegó ninguno legible. */
    private record Seen(String name, String role, Instant at) {
        boolean sameAs(Seen other) {
            return Objects.equals(name, other.name) && role.equals(other.role);
        }
    }
}
