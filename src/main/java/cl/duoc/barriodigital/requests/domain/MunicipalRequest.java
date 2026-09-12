package cl.duoc.barriodigital.requests.domain;

import java.time.Instant;
import java.util.UUID;

public class MunicipalRequest {

    private final String id;
    private final String title;
    private final String description;
    private final String procedureType;
    private RequestStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    public MunicipalRequest(String title, String description, String procedureType) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.description = description;
        this.procedureType = procedureType;
        this.status = RequestStatus.INGRESADO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
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
