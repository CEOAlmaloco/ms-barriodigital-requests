package cl.duoc.barriodigital.requests.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Trámite municipal persistido en Oracle.
 * Guarda descripción, tipo, dirección, solicitante y estado. Al crearse queda en INGRESADO.
 */
@Entity
// El nombre del constraint calza con db/folio-existing-requests.sql. Con ddl-auto update, Hibernate lo recrea por nombre.
// Índices del listado (EP3-30). Hibernate los crea por nombre si faltan; idx_municipal_requests_created ya está en db/schema.sql.
@Table(
        name = "municipal_requests",
        uniqueConstraints = @UniqueConstraint(name = "uk_municipal_requests_folio", columnNames = "folio"),
        indexes = {
                @Index(name = "idx_municipal_requests_solicitante_created", columnList = "solicitante_id, created_at"),
                @Index(name = "idx_municipal_requests_created", columnList = "created_at"),
                @Index(name = "idx_municipal_requests_assignee", columnList = "assignee_id")
        }
)
public class MunicipalRequest {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    /**
     * Número de seguimiento BD-AAAA-NNNNNN (contrato EP3-05). Se asigna una vez en el alta y no cambia.
     * Los trámites anteriores lo reciben con db/folio-existing-requests.sql.
     */
    @Column(name = "folio", nullable = false, updatable = false, length = Folio.LENGTH)
    private String folio;

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

    /**
     * Responsable del trámite (contrato EP3-05, sección 2): el oid de un Funcionario. Null mientras nadie lo toma.
     * Se expone solo a través de RequestView: el Vecino no lo recibe.
     */
    @Column(name = "assignee_id", length = 64)
    private String assigneeId;

    /** Nombre del responsable al asignarlo, como snapshot. */
    @Column(name = "assignee_name", length = 200)
    private String assigneeName;

    /**
     * Plazo de atención del tipo al momento del alta, en días hábiles (EP3-06, sección 1.3). Snapshot: editar el
     * tipo después no lo cambia. Null si el tipo no tenía plazo o si el trámite es anterior a EP3-31.
     */
    @Column(name = "sla_business_days")
    private Integer slaBusinessDays;

    /**
     * Nota de la encuesta de satisfacción (EP3-74): 1 a 5. Null si el titular aún no calificó.
     * Solo aplica a trámites RESUELTO y se escribe una sola vez.
     */
    @Column(name = "satisfaction_score")
    private Integer satisfactionScore;

    /** Comentario opcional de la encuesta. Null si no hubo comentario. Máximo 500 caracteres. */
    @Column(name = "satisfaction_comment", length = 500)
    private String satisfactionComment;

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

    /** Crea el trámite completo con la fecha de ahora. El alta usa el constructor que recibe el instante. */
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
        this(title, description, procedureType, address, solicitanteId, solicitanteNombre, solicitanteEmail,
                procedureLabel, Instant.now());
    }

    /**
     * Crea el trámite en INGRESADO con el instante del alta, leído una sola vez del Clock.
     * Del mismo instante salen created_at, el año del folio y la marca CREADO del historial (EP3-05, sección 1.3).
     */
    public MunicipalRequest(
            String title,
            String description,
            String procedureType,
            String address,
            String solicitanteId,
            String solicitanteNombre,
            String solicitanteEmail,
            String procedureLabel,
            Instant createdAt
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
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
    }

    /**
     * Antes de guardar por primera vez, completa el id, las fechas y el estado si faltan.
     * Nunca pisa created_at ni updated_at ya puestos: el año del folio sale de created_at.
     */
    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
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

    /** Número de seguimiento BD-AAAA-NNNNNN. */
    public String getFolio() {
        return folio;
    }

    /** Asigna el folio en el alta. Una segunda asignación es un error de programación. */
    public void assignFolio(String folio) {
        if (this.folio != null) {
            throw new IllegalStateException("El folio del tramite ya fue asignado");
        }
        this.folio = Objects.requireNonNull(folio, "folio");
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

    /** oid del responsable, o null si nadie lo ha tomado. */
    public String getAssigneeId() {
        return assigneeId;
    }

    /** Nombre del responsable al asignarlo. */
    public String getAssigneeName() {
        return assigneeName;
    }

    /** Indica si el trámite tiene responsable. */
    public boolean hasAssignee() {
        return assigneeId != null;
    }

    /** Deja un nuevo responsable. Nadie devuelve un trámite a "sin responsable" (EP3-05, sección 2). */
    public void assignTo(String assigneeId, String assigneeName) {
        this.assigneeId = Objects.requireNonNull(assigneeId, "assigneeId");
        this.assigneeName = assigneeName;
    }

    /** Indica si hay una imagen adjunta, sin cargar el contenido binario. */
    public boolean isHasAttachment() {
        return hasAttachment;
    }

    public Integer getSlaBusinessDays() {
        return slaBusinessDays;
    }

    /** Guarda el plazo del tipo en el alta. Ya viene validado: entre 1 y 120, o null. */
    public void snapshotSlaBusinessDays(Integer slaBusinessDays) {
        this.slaBusinessDays = slaBusinessDays;
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
        // Es también el instante de la marca ESTADO y de request.status-changed.
        this.updatedAt = StoredInstant.now();
    }

    public Integer getSatisfactionScore() {
        return satisfactionScore;
    }

    public String getSatisfactionComment() {
        return satisfactionComment;
    }

    /** Indica si el titular ya respondió la encuesta. */
    public boolean isRated() {
        return satisfactionScore != null;
    }

    /**
     * Guarda la calificación del titular. score ya validado (1..5); comment null o recortado.
     * Es también el instante de la marca CALIFICACION y de request.rated.
     */
    public void applyRating(int score, String comment) {
        this.satisfactionScore = score;
        this.satisfactionComment = comment;
        this.updatedAt = StoredInstant.now();
    }
}
