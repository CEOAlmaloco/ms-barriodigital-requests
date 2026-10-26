package cl.duoc.barriodigital.requests.messaging;

/**
 * Datos del trámite que viajan en el evento.
 * Lleva id, tipo, etiqueta, dirección y correo. Si un dato no existe, va null: no se inventa.
 */
public record RequestEventPayload(
        String requestId,
        String procedureType,
        String procedureLabel,
        String address,
        String solicitanteEmail
) {
}
