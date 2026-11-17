package cl.duoc.barriodigital.requests.adapter.out.persistence;

import cl.duoc.barriodigital.requests.domain.RequestAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestAttachmentRepository extends JpaRepository<RequestAttachment, String> {
}
