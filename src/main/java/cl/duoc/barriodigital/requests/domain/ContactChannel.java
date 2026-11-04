package cl.duoc.barriodigital.requests.domain;

/** Canal que el funcionario declara. No lo verifica una central telefónica. */
public enum ContactChannel {
    LLAMADA("Llamada"),
    CORREO("Correo"),
    VISITA("Visita"),
    MENSAJE("Mensaje");

    private final String label;

    ContactChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
