-- Esquema de referencia (Oracle Autonomous).
-- Hibernate puede crear/actualizar con ddl-auto=update.

CREATE TABLE municipal_requests (
    id              VARCHAR2(36)   PRIMARY KEY,
    title           VARCHAR2(200)  NOT NULL,
    description     VARCHAR2(2000) NOT NULL,
    procedure_type  VARCHAR2(100)  NOT NULL,
    address         VARCHAR2(300)  NOT NULL,
    solicitante_id  VARCHAR2(64)   NOT NULL,
    status          VARCHAR2(30)   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_municipal_requests_status ON municipal_requests (status);
CREATE INDEX idx_municipal_requests_created ON municipal_requests (created_at);
CREATE INDEX idx_municipal_requests_solicitante ON municipal_requests (solicitante_id);
