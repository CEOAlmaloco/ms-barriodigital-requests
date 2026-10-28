package cl.duoc.barriodigital.requests.domain;

/**
 * Estados de un trámite.
 * Empieza en INGRESADO, puede seguir por ADMITIDO, EN_GESTION y EN_TERRENO, y termina en RESUELTO o RECHAZADO.
 */
public enum RequestStatus {
    INGRESADO("Ingresado"),
    ADMITIDO("Admitido"),
    EN_GESTION("En gestión"),
    EN_TERRENO("En terreno"),
    RESUELTO("Resuelto"),
    RECHAZADO("Rechazado");

    private final String label;

    RequestStatus(String label) {
        this.label = label;
    }

    /** Nombre que se muestra y que queda escrito en el historial. */
    public String label() {
        return label;
    }
}
