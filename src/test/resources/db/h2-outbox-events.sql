-- Solo tests. Igual que en Oracle, outbox_events la crea un script y no Hibernate (EP3-02, sección 2.1).
-- Misma forma que src/main/resources/db/outbox-events.sql, sin ORDER: H2 no lo acepta en un identity.
-- Corre después de que Hibernate crea el esquema (spring.jpa.defer-datasource-initialization).
DROP TABLE IF EXISTS outbox_events;

CREATE TABLE outbox_events (
    event_id       VARCHAR2(36)  NOT NULL,
    event_sequence NUMBER GENERATED ALWAYS AS IDENTITY (START WITH 1 INCREMENT BY 1),
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
