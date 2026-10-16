package cl.duoc.barriodigital.requests.web;

/**
 * Identidad que el BFF reenvía tras validar el JWT (nunca viene del body).
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
    /** EP2-20 lo reenvía en el POST. El email no va en el body. */
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    /** Label del tipo según catalog. El BFF lo manda codificado en URL (UTF-8), solo en el POST. */
    public static final String HEADER_PROCEDURE_LABEL = "X-Procedure-Label";

    /** Identidad sin label del tipo. Solo el alta lo usa. */
    public CallerContext(String userId, String rolesHeader, String userName, String userEmail) {
        this(userId, rolesHeader, userName, userEmail, null);
    }

    /** Identidad sin email. GET y cambio de estado no lo necesitan. */
    public CallerContext(String userId, String rolesHeader, String userName) {
        this(userId, rolesHeader, userName, null, null);
    }

    /** Identidad sin nombre ni email. El alta usa solicitanteId como respaldo del nombre. */
    public CallerContext(String userId, String rolesHeader) {
        this(userId, rolesHeader, null, null, null);
    }

    /** Contrato EP1.5-01: solo Funcionario cambia estado (Admin no). */
    public boolean isFuncionario() {
        return hasRole("Funcionario") || hasRole("ROLE_Funcionario");
    }

    public boolean isStaff() {
        return hasRole("Admin")
                || hasRole("Funcionario")
                || hasRole("Auditor")
                || hasRole("ROLE_Admin")
                || hasRole("ROLE_Funcionario")
                || hasRole("ROLE_Auditor");
    }

    private boolean hasRole(String expected) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        for (String part : rolesHeader.split(",")) {
            if (expected.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Vecino (u otro no staff): solo ve/crea como dueño de sus trámites. */
    public boolean mustFilterOwnRequests() {
        return !isStaff();
    }
}
