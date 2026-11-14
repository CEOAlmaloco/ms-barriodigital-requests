package cl.duoc.barriodigital.requests.domain;

import cl.duoc.barriodigital.requests.service.ImageAttachmentSanitizer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** request_id repite el del comentario: la FK compuesta de db/citizen-comment.sql impide que difieran. */
@Entity
@Table(name = "request_activity_attachments")
public class RequestActivityAttachment {

    @Id
    @Column(name = "activity_id", length = 36, nullable = false)
    private String activityId;

    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @Lob
    @Column(name = "attachment_data", nullable = false)
    private byte[] content;

    @Column(name = "content_type", nullable = false, length = 30)
    private String contentType;

    @Column(name = "file_name", nullable = false, length = 40)
    private String fileName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RequestActivityAttachment() {
    }

    public RequestActivityAttachment(RequestActivity comment, ImageAttachmentSanitizer.SanitizedImage image) {
        if (comment.getKind() != ActivityKind.COMENTARIO || !comment.isHasAttachment()) {
            throw new IllegalArgumentException("Solo un comentario con foto lleva imagen");
        }
        this.activityId = comment.getId();
        this.requestId = comment.getRequestId();
        this.content = image.content();
        this.contentType = image.contentType();
        this.fileName = image.fileName();
        this.createdAt = comment.getOccurredAt();
    }

    public String getActivityId() {
        return activityId;
    }

    public String getRequestId() {
        return requestId;
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
