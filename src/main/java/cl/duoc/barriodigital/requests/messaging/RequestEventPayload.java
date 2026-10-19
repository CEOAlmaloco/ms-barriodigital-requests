package cl.duoc.barriodigital.requests.messaging;

/** Snapshot escalar del trámite; los nulos históricos forman parte del contrato EP2-01. */
public record RequestEventPayload(
        String requestId,
        String procedureType,
        String procedureLabel,
        String address,
        String solicitanteEmail
) {
}
