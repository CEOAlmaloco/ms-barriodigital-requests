-- Esquema de referencia (Oracle Autonomous).
-- Hibernate puede crear/actualizar con ddl-auto=update.

CREATE TABLE municipal_requests (
    id              VARCHAR2(36)   PRIMARY KEY,
    title           VARCHAR2(200)  NOT NULL,
    description     VARCHAR2(2000) NOT NULL,
    procedure_type  VARCHAR2(100)  NOT NULL,
    procedure_label VARCHAR2(120),
    address         VARCHAR2(300)  NOT NULL,
    sector          VARCHAR2(40),
    location_reference VARCHAR2(200),
    latitude        NUMBER,
    longitude       NUMBER,
    location_precision VARCHAR2(20),
    solicitante_id  VARCHAR2(64)   NOT NULL,
    solicitante_nombre VARCHAR2(200),
    solicitante_email  VARCHAR2(254),
    status          VARCHAR2(30)   NOT NULL,
    rejection_reason VARCHAR2(500),
    has_attachment  NUMBER(1) DEFAULT 0 NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE municipal_request_attachments (
    request_id      VARCHAR2(36)  PRIMARY KEY,
    attachment_data BLOB          NOT NULL,
    content_type    VARCHAR2(30)  NOT NULL,
    file_name       VARCHAR2(40)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_request_attachment_request
        FOREIGN KEY (request_id) REFERENCES municipal_requests (id) ON DELETE CASCADE
);

CREATE INDEX idx_municipal_requests_status ON municipal_requests (status);
CREATE INDEX idx_municipal_requests_created ON municipal_requests (created_at);
CREATE INDEX idx_municipal_requests_solicitante ON municipal_requests (solicitante_id);

-- Historial del expediente. Solo se inserta. La API no actualiza ni borra filas.
CREATE TABLE request_activity (
    id               VARCHAR2(36)  PRIMARY KEY,
    request_id       VARCHAR2(36)  NOT NULL,
    occurred_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    kind             VARCHAR2(20)  NOT NULL,
    visibility       VARCHAR2(20)  NOT NULL,
    actor_id         VARCHAR2(64)  NOT NULL,
    actor_name       VARCHAR2(200),
    summary          VARCHAR2(500) NOT NULL,
    from_status      VARCHAR2(30),
    to_status        VARCHAR2(30),
    reason           VARCHAR2(500),
    channel          VARCHAR2(30),
    contact_result   VARCHAR2(40),
    note             VARCHAR2(500),
    next_attempt_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_request_activity_request ON request_activity (request_id);
