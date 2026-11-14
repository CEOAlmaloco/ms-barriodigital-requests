package cl.duoc.barriodigital.requests.persistence;

import cl.duoc.barriodigital.requests.domain.RequestActivityAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RequestActivityAttachmentRepository extends JpaRepository<RequestActivityAttachment, String> {

    Optional<RequestActivityAttachment> findByActivityIdAndRequestId(String activityId, String requestId);
}
