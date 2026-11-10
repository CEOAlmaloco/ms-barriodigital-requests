package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Personal municipal visto por requests (contrato EP3-05, sección 5.1). La plataforma no consulta el directorio
 * de Entra ID: la fila nace en la primera llamada de staff y se actualiza con el nombre y el rol de la última.
 * Los vecinos no se registran (minimización de datos, Ley 21.719).
 */
@Entity
@Table(name = "staff_member")
public class StaffMember {

    /** X-User-Id (oid de Entra). */
    @Id
    @Column(length = 64, nullable = false)
    private String oid;

    /** X-User-Name decodificado, el último visto. */
    @Column(length = 200, nullable = false)
    private String name;

    /** El único rol de staff de X-User-Roles, el último visto. */
    @Column(length = 20, nullable = false)
    private String role;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Lo usa JPA para crear el objeto vacío. */
    protected StaffMember() {
        // JPA
    }

    /** Primera llamada registrada de esta persona. */
    public StaffMember(String oid, String name, String role, Instant seenAt) {
        this.oid = oid;
        this.name = name;
        this.role = role;
        this.firstSeenAt = seenAt;
        this.lastSeenAt = seenAt;
    }

    /** Llamada posterior: deja el nombre y el rol más recientes. */
    public void seen(String name, String role, Instant seenAt) {
        this.name = name;
        this.role = role;
        this.lastSeenAt = seenAt;
    }

    public String getOid() {
        return oid;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
