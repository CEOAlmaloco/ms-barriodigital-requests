package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "municipal_requests")
public class MunicipalRequest {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    /** Autogenerado: procedureType label + direccion. No lo envía el formulario. */
    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "procedure_type", nullable = false, length = 100)
    private String procedureType;

    @Column(nullable = false, length = 300)
    private String address;

    /** oid/sub del JWT (Entra). Lo setea el BFF por header, no el body. */
    @Column(name = "solicitante_id", nullable = false, length = 64)
    private String solicitanteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MunicipalRequest() {
        // JPA
    }

    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId
    ) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.procedureType = procedureType;
        this.address = address;
        this.solicitanteId = solicitanteId;
        this.status = RequestStatus.INGRESADO;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = RequestStatus.INGRESADO;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getProcedureType() {
        return procedureType;
    }

    public String getAddress() {
        return address;
    }

    public String getSolicitanteId() {
        return solicitanteId;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(RequestStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }
}
