package cl.duoc.barriodigital.requests.domain;

import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/** Imagen normalizada asociada al trámite. El contenido no forma parte del JSON de MunicipalRequest. */
@Entity
@Table(name = "municipal_request_attachments")
public class RequestAttachment {

    @Id
    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id")
    private MunicipalRequest request;

    @Lob
    @Column(name = "attachment_data", nullable = false)
    private byte[] content;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "file_name", nullable = false, length = 40)
    private String fileName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RequestAttachment() {
    }

    public RequestAttachment(MunicipalRequest request, ImageAttachmentSanitizer.SanitizedImage image) {
        this.request = request;
        this.content = image.content();
        this.contentType = image.contentType();
        this.fileName = image.fileName();
        this.createdAt = Instant.now();
    }

    public byte[] getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileName() {
        return fileName;
    }
}
