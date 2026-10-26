package cl.duoc.barriodigital.requests.domain;

/**
 * Estados de un trámite.
 * Empieza en INGRESADO, puede seguir por ADMITIDO, EN_GESTION y EN_TERRENO, y termina en RESUELTO o RECHAZADO.
 */
public enum RequestStatus {
    INGRESADO,
    ADMITIDO,
    EN_GESTION,
    EN_TERRENO,
    RESUELTO,
    RECHAZADO
}
