-- =====================================================================================
-- EP3-31: tabla outbox_events (contrato EP3-02, sección 2.1).
-- Oracle Autonomous. Corre UNA vez, ANTES del primer arranque de requests con EP3-31, también en una base nueva.
--
-- ddl-auto: update no sabe crear event_sequence como identity con ORDER: si Hibernate crea la tabla primero,
-- la crea sin identity y la primera alta falla con ORA-01400. Por eso la tabla nace de este script.
-- Las columnas actor_role (request_activity) y sla_business_days (municipal_requests) son nullable:
-- las agrega Hibernate al arrancar y no necesitan script.
-- =====================================================================================

-- Detiene el script ante el primer error, en SQLcl, SQL*Plus y el worksheet de SQL Developer.
WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: si la tabla ya existe, cuántas filas tiene y si event_sequence es identity.
SELECT t.table_name,
       (SELECT COUNT(*) FROM user_tab_identity_cols i
        WHERE i.table_name = t.table_name AND i.column_name = 'EVENT_SEQUENCE') AS es_identity
FROM user_tables t
WHERE t.table_name = 'OUTBOX_EVENTS';

-- 1. Si Hibernate alcanzó a crear la tabla sin identity, se elimina. Está vacía: sin identity no entra ninguna fila.
--    Con filas, o ya con identity, el script se detiene y no toca nada.
DECLARE
    v_tables     NUMBER;
    v_identity   NUMBER;
    v_rows       NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_tables FROM user_tables WHERE table_name = 'OUTBOX_EVENTS';
    IF v_tables = 1 THEN
        SELECT COUNT(*) INTO v_identity FROM user_tab_identity_cols
        WHERE table_name = 'OUTBOX_EVENTS' AND column_name = 'EVENT_SEQUENCE';
        EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM outbox_events' INTO v_rows;
        IF v_identity = 1 OR v_rows > 0 THEN
            RAISE_APPLICATION_ERROR(-20031, 'outbox_events ya existe con identity o con filas: no se modifica');
        END IF;
        EXECUTE IMMEDIATE 'DROP TABLE outbox_events PURGE';
    END IF;
END;
/

-- 2. La tabla. ORDER mantiene el orden real de event_sequence aunque Oracle Autonomous corra en varias instancias.
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

-- 3. Lectura de pendientes en orden (relay, EP3-32).
CREATE INDEX idx_outbox_pending ON outbox_events (published_at, event_sequence);

-- Comprobación: identity con ORDER_FLAG: Y, las dos restricciones y el índice.
SELECT column_name, generation_type, identity_options FROM user_tab_identity_cols
WHERE table_name = 'OUTBOX_EVENTS';
SELECT constraint_name, constraint_type FROM user_constraints
WHERE table_name = 'OUTBOX_EVENTS' AND constraint_name IN ('PK_OUTBOX_EVENTS', 'UK_OUTBOX_EVENTS_SEQUENCE');
SELECT index_name FROM user_indexes WHERE table_name = 'OUTBOX_EVENTS' AND index_name = 'IDX_OUTBOX_PENDING';
