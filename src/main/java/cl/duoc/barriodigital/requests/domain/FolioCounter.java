package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Último número de folio entregado en un año (contrato EP3-05, sección 1.2).
 * Se usa una tabla y no secuencias de Oracle para que la numeración no tenga huecos y reinicie cada año.
 * Los tipos calzan con db/folio-existing-requests.sql.
 */
@Entity
@Table(name = "folio_counter")
public class FolioCounter {

    /** Año del folio. Se evita year, palabra reservada en H2. */
    @Id
    @Column(name = "folio_year", columnDefinition = "NUMBER(4)")
    private Integer folioYear;

    /** Último número entregado. Se evitan value y last_value, reservadas en H2 y Oracle. */
    @Column(name = "last_number", nullable = false, columnDefinition = "NUMBER")
    private long lastNumber;

    /** Lo usa JPA para crear el objeto vacío. */
    protected FolioCounter() {
        // JPA
    }

    /** Fila nueva de un año, todavía sin números entregados. */
    public FolioCounter(int folioYear) {
        this.folioYear = folioYear;
        this.lastNumber = 0;
    }

    /** Año de la fila. */
    public int getFolioYear() {
        return folioYear;
    }

    /** Último número entregado en el año. */
    public long getLastNumber() {
        return lastNumber;
    }

    /** Entrega el número siguiente. Solo se llama con la fila bloqueada. */
    public long takeNext() {
        lastNumber = lastNumber + 1;
        return lastNumber;
    }
}
