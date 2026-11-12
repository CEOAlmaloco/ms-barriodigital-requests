package cl.duoc.barriodigital.requests.messaging;

/**
 * Datos del trámite que viajan en el evento.
 * Lleva id, folio, tipo, etiqueta, dirección, correo y motivo de rechazo. Si un dato no existe, va null: no se inventa.
 * El folio es un campo nuevo compatible (EP3-05, sección 1.5): notify lee el payload campo por campo.
 * rejectionReason solo viene en email.rejected (EP3-33). interventionReason nunca viaja a notify (EP3-04, sección 6.3).
 */
public record RequestEventPayload(
        String requestId,
        String folio,
        String procedureType,
        String procedureLabel,
        String address,
        String solicitanteEmail,
        String rejectionReason
) {
}
