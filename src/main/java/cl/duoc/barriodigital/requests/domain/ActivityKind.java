package cl.duoc.barriodigital.requests.domain;

/**
 * Tipo de marca del expediente. No es un log técnico.
 */
public enum ActivityKind {
    CREADO,
    ESTADO,
    GESTION,
    /** Toma, asignación o reasignación del responsable (contrato EP3-05, sección 6). */
    ASIGNACION,
    /** Encuesta de satisfacción del titular (EP3-74). */
    CALIFICACION
}
