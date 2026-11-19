package cl.duoc.barriodigital.requests.adapter.in.web;

import org.springframework.http.HttpStatus;

/**
 * Códigos estables de error, cubiertos todos los del cambio de estado y la admisión (EP3-04, sección 5). Los
 * consumidores comparan el código, no el texto. Un código no se renombra ni se reutiliza, y no repite los códigos de
 * admisión del BFF, que el frontend lee del mismo campo.
 */
public enum RequestsErrorCode {

    USER_ID_REQUIRED(HttpStatus.BAD_REQUEST,
            "Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT."),
    ROLE_REQUIRED(HttpStatus.FORBIDDEN, "No tienes un rol asignado en BarrioDigital"),
    SINGLE_ROLE_REQUIRED(HttpStatus.FORBIDDEN, "El usuario debe tener un solo rol"),
    STATUS_CHANGE_FORBIDDEN(HttpStatus.FORBIDDEN, "Solo un Funcionario puede cambiar el estado"),
    STATUS_REQUIRED(HttpStatus.BAD_REQUEST, "status es obligatorio"),
    STATUS_INVALID(HttpStatus.BAD_REQUEST,
            "status invalido. Valores: INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO"),
    CANCEL_REQUIRES_CANCEL_ENDPOINT(HttpStatus.BAD_REQUEST,
            "La cancelacion del titular usa POST /api/requests/{id}/cancel"),
    REJECTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "rejectionReason es obligatorio cuando el estado es RECHAZADO"),
    REJECTION_REASON_TOO_LONG(HttpStatus.BAD_REQUEST, "rejectionReason no puede superar 500 caracteres"),
    INTERVENTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "interventionReason es obligatorio para el Admin"),
    INTERVENTION_REASON_TOO_LONG(HttpStatus.BAD_REQUEST, "interventionReason no puede superar 500 caracteres"),
    /** Body que no pasa @Valid o no se puede leer. Conserva el mensaje que genera Spring. */
    REQUEST_BODY_INVALID(HttpStatus.BAD_REQUEST, null),
    REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "Tramite no encontrado"),
    NOT_ASSIGNEE(HttpStatus.FORBIDDEN, "Solo el responsable del tramite puede operarlo"),
    /** El mensaje nombra los dos estados. */
    STATUS_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, null);

    private final HttpStatus status;
    private final String message;

    RequestsErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    /** Mensaje fijo del código, o null si se arma en cada caso. */
    public String message() {
        return message;
    }
}
