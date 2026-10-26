package cl.duoc.barriodigital.requests.web;

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

    /** Indica si el rol es Funcionario. Solo ese rol cambia el estado; Admin no. */
    public boolean isFuncionario() {
        return hasRole("Funcionario") || hasRole("ROLE_Funcionario");
    }

    /** Indica si el rol es Admin, Funcionario o Auditor. */
    public boolean isStaff() {
        return hasRole("Admin")
                || hasRole("Funcionario")
                || hasRole("Auditor")
                || hasRole("ROLE_Admin")
                || hasRole("ROLE_Funcionario")
                || hasRole("ROLE_Auditor");
    }

    /** Busca el rol en el header, separado por comas, sin distinguir mayúsculas. */
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

    /** Quien no es personal solo ve sus propios trámites. */
    public boolean mustFilterOwnRequests() {
        return !isStaff();
    }
}
