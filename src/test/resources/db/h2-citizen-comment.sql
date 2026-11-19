-- Solo tests. Las restricciones de db/citizen-comment.sql que Hibernate no crea.
-- Corre después de que Hibernate crea el esquema de H2, que ya trae las tablas.
ALTER TABLE request_activity ADD CONSTRAINT IF NOT EXISTS uk_request_activity_request UNIQUE (id, request_id);

ALTER TABLE request_activity_attachments ADD CONSTRAINT IF NOT EXISTS fk_request_activity_attachment_comment
    FOREIGN KEY (activity_id, request_id) REFERENCES request_activity (id, request_id);
