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

/**
 * Trámite municipal persistido en Oracle.
 * Guarda descripción, tipo, dirección, solicitante y estado. Al crearse queda en INGRESADO.
 */
@Entity
@Table(name = "municipal_requests")
public class MunicipalRequest {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    /** Título generado con la etiqueta del tipo y la dirección. No lo envía el formulario. */
    @Column(nullable = false, length = 200)
    private String title;

    /** Texto que escribió quien pidió el trámite. */
    @Column(nullable = false, length = 2000)
    private String description;

    /** Código del tipo de trámite, por ejemplo bache. */
    @Column(name = "procedure_type", nullable = false, length = 100)
    private String procedureType;

    /**
     * Etiqueta del tipo al crear el trámite, tal como estaba en catalog. Mismo largo que label en catalog.
     * Puede ser null en filas anteriores; Hibernate update agrega la columna.
     */
    @Column(name = "procedure_label", length = 120)
    private String procedureLabel;

    /** Dirección del problema. */
    @Column(nullable = false, length = 300)
    private String address;

    /** Código de sector de la comuna. Puede faltar en trámites anteriores. */
    @Column(length = 40)
    private String sector;

    /** Referencia para encontrar el lugar, por ejemplo un poste o un local. */
    @Column(name = "location_reference", length = 200)
    private String locationReference;

    /** Latitud del punto, si quien ingresó el trámite lo marcó. */
    @Column
    private Double latitude;

    /** Longitud del punto, si quien ingresó el trámite lo marcó. */
    @Column
    private Double longitude;

    /** EXACTO o APROXIMADO. Solo cuando hay punto. */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_precision", length = 20)
    private LocationPrecision locationPrecision;

    /** Identificador del solicitante (oid o sub de Entra). Lo envía el BFF por header, no el body. */
    @Column(name = "solicitante_id", nullable = false, length = 64)
    private String solicitanteId;

    /**
     * Nombre del solicitante al crear el trámite. Los GET no vuelven a leer el JWT.
     * Puede ser null en filas anteriores a ese dato.
     */
    @Column(name = "solicitante_nombre", length = 200)
    private String solicitanteNombre;

    /**
     * Correo del solicitante al crear el trámite. Los GET no vuelven a leer el JWT.
     * Puede ser null en filas anteriores; Hibernate update agrega la columna.
     */
    @Column(name = "solicitante_email", length = 254)
    private String solicitanteEmail;

    /** Estado actual del trámite. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RequestStatus status;

    /** Motivo del rechazo. Solo se guarda cuando el estado es RECHAZADO, con un máximo de 500 caracteres. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Indica si el trámite tiene una imagen privada asociada en su propia tabla. */
    @Column(name = "has_attachment", nullable = false, columnDefinition = "NUMBER(1) DEFAULT 0")
    private boolean hasAttachment;

    /** Momento en que se creó el trámite. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Momento de la última modificación. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Lo usa JPA para crear el objeto vacío. */
    protected MunicipalRequest() {
        // JPA
    }

    /** Crea un trámite sin nombre, correo ni etiqueta del tipo. */
    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId
    ) {
        this(title, description, procedureType, address, solicitanteId, null);
    }

    /** Crea un trámite con el nombre del solicitante y sin correo ni etiqueta del tipo. */
    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId,
            String solicitanteNombre
    ) {
        this(title, description, procedureType, address, solicitanteId, solicitanteNombre, null);
    }

    /** Crea un trámite con nombre y correo, sin la etiqueta del tipo. */
    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId,
            String solicitanteNombre,
            String solicitanteEmail
    ) {
        this(title, description, procedureType, address, solicitanteId, solicitanteNombre, solicitanteEmail, null);
    }

    /** Crea el trámite completo y lo deja en INGRESADO, con la fecha de ahora. */
    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId,
            String solicitanteNombre,
            String solicitanteEmail,
            String procedureLabel
    ) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.procedureType = procedureType;
        this.procedureLabel = procedureLabel;
        this.address = address;
        this.solicitanteId = solicitanteId;
        this.solicitanteNombre = solicitanteNombre;
        this.solicitanteEmail = solicitanteEmail;
        this.status = RequestStatus.INGRESADO;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Antes de guardar por primera vez, completa el id, las fechas y el estado si faltan. */
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

    /** En cada actualización, marca la hora de ahora. */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Identificador del trámite. */
    public String getId() {
        return id;
    }

    /** Título armado con el tipo y la dirección. */
    public String getTitle() {
        return title;
    }

    /** Texto que escribió quien pidió el trámite. */
    public String getDescription() {
        return description;
    }

    /** Código del tipo de trámite. */
    public String getProcedureType() {
        return procedureType;
    }

    /** Etiqueta del tipo tal como estaba en catalog al crearlo. */
    public String getProcedureLabel() {
        return procedureLabel;
    }

    /** Dirección del problema. */
    public String getAddress() {
        return address;
    }

    /** Código de sector, o null si no se indicó. */
    public String getSector() {
        return sector;
    }

    /** Referencia para encontrar el lugar. */
    public String getLocationReference() {
        return locationReference;
    }

    /** Latitud del punto, o null si no hay punto. */
    public Double getLatitude() {
        return latitude;
    }

    /** Longitud del punto, o null si no hay punto. */
    public Double getLongitude() {
        return longitude;
    }

    /** Si el punto es exacto o aproximado. */
    public LocationPrecision getLocationPrecision() {
        return locationPrecision;
    }

    /** Guarda sector, referencia y punto. No reemplaza la dirección escrita. */
    public void placeLocation(
            String sector,
            String locationReference,
            Double latitude,
            Double longitude,
            LocationPrecision locationPrecision
    ) {
        this.sector = sector;
        this.locationReference = locationReference;
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationPrecision = locationPrecision;
    }

    /** Id de quien pidió el trámite. */
    public String getSolicitanteId() {
        return solicitanteId;
    }

    /** Nombre de quien pidió el trámite, guardado al crearlo. */
    public String getSolicitanteNombre() {
        return solicitanteNombre;
    }

    /** Correo de quien pidió el trámite, guardado al crearlo. */
    public String getSolicitanteEmail() {
        return solicitanteEmail;
    }

    /** Estado actual. */
    public RequestStatus getStatus() {
        return status;
    }

    /** Momento en que se creó. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Momento de la última modificación. */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Motivo, solo si el estado es RECHAZADO. */
    public String getRejectionReason() {
        return rejectionReason;
    }

    /** Indica si hay una imagen adjunta, sin cargar el contenido binario. */
    public boolean isHasAttachment() {
        return hasAttachment;
    }

    /** Actualiza la presencia de la imagen al crear el trámite y su adjunto en la misma transacción. */
    public void setHasAttachment(boolean hasAttachment) {
        this.hasAttachment = hasAttachment;
    }

    /** Cambia el estado y marca la hora. No toca el motivo de rechazo. */
    public void setStatus(RequestStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    /** Cambia el estado. El motivo se guarda solo si el nuevo estado es RECHAZADO; si no, queda null. */
    public void applyStatus(RequestStatus next, String rejectionReason) {
        this.status = next;
        this.rejectionReason = next == RequestStatus.RECHAZADO ? rejectionReason : null;
        this.updatedAt = Instant.now();
    }
}
