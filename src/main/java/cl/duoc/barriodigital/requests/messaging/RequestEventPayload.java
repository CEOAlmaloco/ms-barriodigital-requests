package cl.duoc.barriodigital.requests.messaging;

/**
 * Datos del trámite que viajan en el evento.
 * Lleva id, folio, tipo, etiqueta, dirección y correo. Si un dato no existe, va null: no se inventa.
 * El folio es un campo nuevo compatible (EP3-05, sección 1.5): notify lee el payload campo por campo.
 */
public record RequestEventPayload(
        String requestId,
        String folio,
        String procedureType,
        String procedureLabel,
        String address,
        String solicitanteEmail
) {
}
