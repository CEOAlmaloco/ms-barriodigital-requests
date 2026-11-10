package cl.duoc.barriodigital.requests.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Identidad de quien llama, reenviada por el BFF en headers después de validar el JWT.
 * Este servicio no recibe el token.
 */
public record CallerContext(
        String userId,
        String rolesHeader,
        String userName,
        String userEmail,
        String procedureLabel
) {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";
    public static final String HEADER_USER_NAME = "X-User-Name";
    /** Correo del solicitante. El BFF lo reenvía en el POST; no va en el body. */
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    /** Etiqueta del tipo según catalog. El BFF la manda codificada en URL (UTF-8), solo en el POST. */
    public static final String HEADER_PROCEDURE_LABEL = "X-Procedure-Label";

    public static final String VECINO = "Vecino";
    public static final String FUNCIONARIO = "Funcionario";
    public static final String ADMIN = "Admin";
    public static final String AUDITOR = "Auditor";
    /** Los App Roles de Entra ID que reconoce BarrioDigital (EP3-04). */
    public static final List<String> ROLES = List.of(VECINO, FUNCIONARIO, ADMIN, AUDITOR);

    static final String NO_ROLE = "No tienes un rol asignado en BarrioDigital";
    static final String MANY_ROLES = "El usuario debe tener un solo rol";
    private static final String ROLE_PREFIX = "ROLE_";

    /** Identidad sin etiqueta del tipo. */
    public CallerContext(String userId, String rolesHeader, String userName, String userEmail) {
        this(userId, rolesHeader, userName, userEmail, null);
    }

    /** Identidad sin correo. Consultar y cambiar el estado no lo usan. */
    public CallerContext(String userId, String rolesHeader, String userName) {
        this(userId, rolesHeader, userName, null, null);
    }

    /** Identidad solo con id y roles. El alta usa el id como respaldo del nombre. */
    public CallerContext(String userId, String rolesHeader) {
        this(userId, rolesHeader, null, null, null);
    }

    /**
     * Los roles de BarrioDigital que trae el header, con su nombre canónico y sin el prefijo ROLE_.
     * Un valor que no es uno de los cuatro roles no cuenta.
     */
    public List<String> roles() {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        for (String part : rolesHeader.split(",")) {
            String value = part.trim();
            if (value.regionMatches(true, 0, ROLE_PREFIX, 0, ROLE_PREFIX.length())) {
                value = value.substring(ROLE_PREFIX.length());
            }
            for (String role : ROLES) {
                if (role.equalsIgnoreCase(value) && !found.contains(role)) {
                    found.add(role);
                }
            }
        }
        return List.copyOf(found);
    }

    /** El único rol de quien llama, o null si no trae ninguno o trae más de uno (EP3-04, sección 4). */
    public String role() {
        List<String> roles = roles();
        return roles.size() == 1 ? roles.get(0) : null;
    }

    /** Exige exactamente uno de los cuatro roles. Sin rol o con más de uno responde 403. */
    public void requireSingleRole() {
        List<String> roles = roles();
        if (roles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_ROLE);
        }
        if (roles.size() > 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MANY_ROLES);
        }
    }

    /** Indica si el único rol es Vecino. */
    public boolean isVecino() {
        return VECINO.equals(role());
    }

    /** Indica si el único rol es Funcionario. Opera los trámites de los que es responsable. */
    public boolean isFuncionario() {
        return FUNCIONARIO.equals(role());
    }

    /** Indica si el único rol es Admin. Opera cualquier trámite como intervención, con motivo. */
    public boolean isAdmin() {
        return ADMIN.equals(role());
    }

    /** Indica si el único rol es Auditor. No opera y no recibe el correo del solicitante (EP3-04, sección 6.1). */
    public boolean isAuditor() {
        return AUDITOR.equals(role());
    }

    /** Indica si el único rol es Admin, Funcionario o Auditor. */
    public boolean isStaff() {
        return isFuncionario() || isAdmin() || isAuditor();
    }

    /** Quien no es personal solo ve sus propios trámites. */
    public boolean mustFilterOwnRequests() {
        return !isStaff();
    }
}
