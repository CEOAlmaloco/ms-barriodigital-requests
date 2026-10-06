package cl.duoc.barriodigital.requests.web;

/**
 * Identidad que el BFF reenvía tras validar el JWT (nunca viene del body).
 */
public record CallerContext(String userId, String rolesHeader) {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

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
