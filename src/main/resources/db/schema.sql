-- Esquema de referencia EP1-14 (Oracle Autonomous).
-- Hibernate puede crear/actualizar la tabla con ddl-auto=update.
-- También puedes ejecutar esto en Database Actions → SQL (usuario de la app).

CREATE TABLE municipal_requests (
    id              VARCHAR2(36)   PRIMARY KEY,
    title           VARCHAR2(200)  NOT NULL,
    description     VARCHAR2(2000) NOT NULL,
    procedure_type  VARCHAR2(100)  NOT NULL,
    status          VARCHAR2(30)   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_municipal_requests_status ON municipal_requests (status);
CREATE INDEX idx_municipal_requests_created ON municipal_requests (created_at);
