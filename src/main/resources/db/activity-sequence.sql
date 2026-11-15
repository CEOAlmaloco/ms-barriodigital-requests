-- =====================================================================================
-- EP3-66: orden del expediente en request_activity.
-- Oracle Autonomous. Corre UNA vez, con requests detenido: no puede haber inserts mientras se numeran las filas y se
-- crea la secuencia. Se puede volver a correr, también tras una ejecución interrumpida: completa lo que falta.
--
--   1. Agrega activity_sequence si no existe.
--   2. Numera las filas que no tienen valor por occurred_at e id, los únicos datos disponibles del historial.
--      El orden real de las operaciones queda garantizado para las actividades registradas después.
--   3. Crea request_activity_seq con ORDER desde el máximo más uno y la deja como valor automático de la columna.
--   4. Agrega uk_request_activity_sequence.
-- Una base nueva creada con schema.sql no lo necesita. Si Hibernate creó la tabla, este script completa la columna.
-- =====================================================================================

-- Detiene el script ante el primer error, en SQLcl, SQL*Plus y el worksheet de SQL Developer.
WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: actividades existentes.
SELECT COUNT(*) AS actividades FROM request_activity;

DECLARE
    v_column          NUMBER;
    v_nullable        VARCHAR2(1);
    v_default_on_null VARCHAR2(3);
    v_base            NUMBER;
    v_next            NUMBER;
    v_current         NUMBER;
    v_sequence        NUMBER;
    v_unique          NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_column FROM user_tab_columns
    WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'ACTIVITY_SEQUENCE';
    IF v_column = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE request_activity ADD (activity_sequence NUMBER(19))';
    END IF;

    EXECUTE IMMEDIATE 'SELECT NVL(MAX(activity_sequence), 0) FROM request_activity' INTO v_base;
    EXECUTE IMMEDIATE q'[
        MERGE INTO request_activity t
        USING (
            SELECT id, :base + ROW_NUMBER() OVER (ORDER BY occurred_at, id) AS numbered
            FROM request_activity
            WHERE activity_sequence IS NULL
        ) s
        ON (t.id = s.id)
        WHEN MATCHED THEN UPDATE SET t.activity_sequence = s.numbered]' USING v_base;
    COMMIT;

    EXECUTE IMMEDIATE 'SELECT NVL(MAX(activity_sequence), 0) + 1 FROM request_activity' INTO v_next;
    SELECT COUNT(*) INTO v_sequence FROM user_sequences WHERE sequence_name = 'REQUEST_ACTIVITY_SEQ';
    IF v_sequence = 1 THEN
        -- Una secuencia que quedó por debajo de las filas numeradas repetiría valores: se recrea.
        EXECUTE IMMEDIATE 'SELECT request_activity_seq.NEXTVAL FROM dual' INTO v_current;
        IF v_current < v_next THEN
            EXECUTE IMMEDIATE 'DROP SEQUENCE request_activity_seq';
            v_sequence := 0;
        END IF;
    END IF;
    IF v_sequence = 0 THEN
        EXECUTE IMMEDIATE 'CREATE SEQUENCE request_activity_seq START WITH ' || v_next
            || ' INCREMENT BY 1 ORDER NOCYCLE';
    ELSE
        EXECUTE IMMEDIATE 'ALTER SEQUENCE request_activity_seq ORDER';
    END IF;

    SELECT nullable, default_on_null INTO v_nullable, v_default_on_null FROM user_tab_columns
    WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'ACTIVITY_SEQUENCE';
    IF v_default_on_null = 'NO' THEN
        -- DEFAULT ON NULL agrega su propio NOT NULL; el que dejó Hibernate se quita antes.
        IF v_nullable = 'N' THEN
            EXECUTE IMMEDIATE 'ALTER TABLE request_activity MODIFY (activity_sequence NULL)';
        END IF;
        EXECUTE IMMEDIATE
            'ALTER TABLE request_activity MODIFY (activity_sequence DEFAULT ON NULL request_activity_seq.NEXTVAL)';
    END IF;

    SELECT COUNT(*) INTO v_unique FROM user_constraints
    WHERE table_name = 'REQUEST_ACTIVITY' AND constraint_name = 'UK_REQUEST_ACTIVITY_SEQUENCE';
    IF v_unique = 0 THEN
        EXECUTE IMMEDIATE
            'ALTER TABLE request_activity ADD CONSTRAINT uk_request_activity_sequence UNIQUE (activity_sequence)';
    END IF;
END;
/

-- Comprobación: columna NOT NULL con DEFAULT ON NULL, secuencia con ORDER_FLAG Y, restricción validada y ninguna
-- fila sin valor.
SELECT column_name, nullable, default_on_null, data_default FROM user_tab_columns
WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'ACTIVITY_SEQUENCE';
SELECT sequence_name, order_flag, last_number FROM user_sequences WHERE sequence_name = 'REQUEST_ACTIVITY_SEQ';
SELECT constraint_name, status, validated FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY' AND constraint_name = 'UK_REQUEST_ACTIVITY_SEQUENCE';
SELECT COUNT(*) AS sin_secuencia FROM request_activity WHERE activity_sequence IS NULL;
