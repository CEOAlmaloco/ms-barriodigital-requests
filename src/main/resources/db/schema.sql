-- Esquema de referencia (Oracle Autonomous).
-- Hibernate puede crear/actualizar con ddl-auto=update.

CREATE TABLE municipal_requests (
    id              VARCHAR2(36)   PRIMARY KEY,
    folio           VARCHAR2(14 CHAR) NOT NULL,
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
    assignee_id     VARCHAR2(64),
    assignee_name   VARCHAR2(200),
    sla_business_days NUMBER(10),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_municipal_requests_folio UNIQUE (folio)
);

-- Último número de folio entregado por año (EP3-05). Las bases con trámites anteriores usan folio-existing-requests.sql.
CREATE TABLE folio_counter (
    folio_year  NUMBER(4) NOT NULL,
    last_number NUMBER    NOT NULL,
    CONSTRAINT pk_folio_counter PRIMARY KEY (folio_year)
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

-- Índices del listado (EP3-30), los mismos que declara MunicipalRequest: el del Vecino, el orden por defecto y la bandeja.
CREATE INDEX idx_municipal_requests_solicitante_created ON municipal_requests (solicitante_id, created_at);
CREATE INDEX idx_municipal_requests_created ON municipal_requests (created_at);
CREATE INDEX idx_municipal_requests_assignee ON municipal_requests (assignee_id);

-- Historial del expediente. Solo se inserta. La API no actualiza ni borra filas.
CREATE TABLE request_activity (
    id               VARCHAR2(36)  PRIMARY KEY,
    request_id       VARCHAR2(36)  NOT NULL,
    occurred_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    kind             VARCHAR2(20)  NOT NULL,
    visibility       VARCHAR2(20)  NOT NULL,
    actor_id         VARCHAR2(64),
    actor_name       VARCHAR2(200),
    actor_role       VARCHAR2(20),
    summary          VARCHAR2(500) NOT NULL,
    from_status      VARCHAR2(30),
    to_status        VARCHAR2(30),
    reason           VARCHAR2(500),
    channel          VARCHAR2(30),
    contact_result   VARCHAR2(40),
    note             VARCHAR2(500),
    next_attempt_at  TIMESTAMP WITH TIME ZONE,
    intervention_reason    VARCHAR2(500),
    assignee_id            VARCHAR2(64),
    assignee_name          VARCHAR2(200),
    previous_assignee_id   VARCHAR2(64),
    previous_assignee_name VARCHAR2(200),
    CONSTRAINT ck_request_activity_kind CHECK (kind IN ('CREADO', 'ESTADO', 'GESTION', 'ASIGNACION')),
    -- Solo el hito público de asignación va sin actor (EP3-05). Las bases existentes usan assignment-request-activity.sql.
    CONSTRAINT ck_request_activity_actor
        CHECK (actor_id IS NOT NULL OR (kind = 'ASIGNACION' AND visibility = 'PUBLICO'))
);

CREATE INDEX idx_request_activity_request ON request_activity (request_id);

-- Outbox de eventos Kafka (EP3-02, sección 2.1). La crea db/outbox-events.sql antes del primer arranque:
-- Hibernate no sabe crear el identity con ORDER.
CREATE TABLE outbox_events (
    event_id       VARCHAR2(36)  NOT NULL,
    event_sequence NUMBER GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1 ORDER),
    aggregate_id   VARCHAR2(36)  NOT NULL,
    event_type     VARCHAR2(60)  NOT NULL,
    payload        CLOB          NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE,
    attempts       NUMBER(10)    DEFAULT 0 NOT NULL,
    last_error     VARCHAR2(500),
    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id),
    CONSTRAINT uk_outbox_events_sequence UNIQUE (event_sequence)
);

CREATE INDEX idx_outbox_pending ON outbox_events (published_at, event_sequence);

-- Personal municipal visto por requests (EP3-05, sección 5). Los vecinos no se registran.
CREATE TABLE staff_member (
    oid           VARCHAR2(64)  PRIMARY KEY,
    name          VARCHAR2(200) NOT NULL,
    role          VARCHAR2(20)  NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
