package cl.duoc.barriodigital.requests.domain;

/** Resultado declarado de una gestión. No demuestra que el vecino haya escuchado. */
public enum ContactResult {
    CONTESTO("Contestó"),
    NO_CONTESTA("No contesta"),
    NUMERO_INCORRECTO("Número incorrecto"),
    BUZON("Buzón de voz"),
    CORREO("Contacto por correo"),
    CONFIRMADO("Contacto confirmado"),
    VISITA_FALLIDA("Visita fallida");

    private final String label;

    ContactResult(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
