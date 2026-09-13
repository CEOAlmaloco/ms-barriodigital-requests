package cl.duoc.barriodigital.requests.web;

/**
 * Identidad que el BFF reenvía tras validar el JWT (nunca viene del body).
 */
public record CallerContext(String userId, String rolesHeader) {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    public boolean isStaff() {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        String[] parts = rolesHeader.split(",");
        for (String part : parts) {
            String role = part.trim();
            if (role.equalsIgnoreCase("Admin")
                    || role.equalsIgnoreCase("Funcionario")
                    || role.equalsIgnoreCase("Auditor")
                    || role.equalsIgnoreCase("ROLE_Admin")
                    || role.equalsIgnoreCase("ROLE_Funcionario")
                    || role.equalsIgnoreCase("ROLE_Auditor")) {
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
