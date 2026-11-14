-- =====================================================================================
-- Comentario y fotos del vecino (contrato EP3-64, sección 5).
-- Oracle Autonomous. Corre ANTES de desplegar la versión de requests que emite request.citizen-comment.
-- Se puede volver a correr, también tras una ejecución interrumpida: crea lo que falta y verifica lo que ya
-- existe. Una restricción con el nombre esperado y otra definición detiene el script sin tocarla.
-- No borra filas: solo reemplaza el CHECK de kind y completa con 0 un has_attachment nulo.
-- Una base nueva no lo necesita si se crea con schema.sql.
-- =====================================================================================

SET SERVEROUTPUT ON
-- Detiene el script ante el primer error, en SQLcl, SQL*Plus y el worksheet de SQL Developer. FAILURE hace que
-- la salida sea distinta de 0: sin él, un error termina con código 0.
WHENEVER SQLERROR EXIT FAILURE ROLLBACK

-- Vista previa, solo lectura: las restricciones de las dos tablas y cualquier otra con los nombres del script.
SELECT table_name, constraint_name, constraint_type, status, validated, r_constraint_name, search_condition_vc
FROM user_constraints
WHERE table_name IN ('REQUEST_ACTIVITY', 'REQUEST_ACTIVITY_ATTACHMENTS')
   OR constraint_name IN (
       'CK_REQUEST_ACTIVITY_KIND', 'CK_REQUEST_ACTIVITY_HAS_ATTACHMENT', 'UK_REQUEST_ACTIVITY_REQUEST',
       'PK_REQUEST_ACTIVITY_ATTACHMENTS', 'CK_REQUEST_ACTIVITY_ATTACHMENT_TYPE', 'FK_REQUEST_ACTIVITY_ATTACHMENT_COMMENT')
ORDER BY table_name, constraint_type, constraint_name;

DECLARE
    TYPE names_t IS TABLE OF VARCHAR2(128);
    v_names     names_t;
    v_count     NUMBER;
    v_type      user_tab_columns.data_type%TYPE;
    v_precision user_tab_columns.data_precision%TYPE;
    v_nullable  user_tab_columns.nullable%TYPE;

    PROCEDURE fail(p_message VARCHAR2) IS
    BEGIN
        RAISE_APPLICATION_ERROR(-20064, SUBSTR(p_message, 1, 2000));
    END;

    -- Oracle guarda la condición tal como se escribió: se compara sin mayúsculas, espacios ni comillas.
    FUNCTION norm(p_text VARCHAR2) RETURN VARCHAR2 IS
    BEGIN
        RETURN REGEXP_REPLACE(UPPER(p_text), '[[:space:]"]', '');
    END;

    FUNCTION table_of(p_constraint VARCHAR2) RETURN VARCHAR2 IS
        v_table user_constraints.table_name%TYPE;
    BEGIN
        SELECT table_name INTO v_table FROM user_constraints WHERE constraint_name = p_constraint;
        RETURN v_table;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RETURN NULL;
    END;

    FUNCTION columns_of(p_constraint VARCHAR2) RETURN VARCHAR2 IS
        v_columns VARCHAR2(4000);
    BEGIN
        SELECT LISTAGG(column_name, ',') WITHIN GROUP (ORDER BY position) INTO v_columns
        FROM user_cons_columns WHERE constraint_name = p_constraint;
        RETURN v_columns;
    END;

    -- Crea la restricción si falta. Si existe, exige tabla, tipo, columnas, condición, referencia y estado.
    PROCEDURE ensure(
        p_name       VARCHAR2,
        p_table      VARCHAR2,
        p_type       VARCHAR2,
        p_columns    VARCHAR2,
        p_condition  VARCHAR2,
        p_referenced VARCHAR2,
        p_ddl        VARCHAR2
    ) IS
        v_table      user_constraints.table_name%TYPE := table_of(p_name);
        v_kind       user_constraints.constraint_type%TYPE;
        v_condition  VARCHAR2(4000);
        v_referenced user_constraints.r_constraint_name%TYPE;
        v_status     user_constraints.status%TYPE;
        v_validated  user_constraints.validated%TYPE;
    BEGIN
        IF v_table IS NULL THEN
            EXECUTE IMMEDIATE p_ddl;
            DBMS_OUTPUT.PUT_LINE('Creada: ' || p_name);
            RETURN;
        END IF;
        IF v_table <> p_table THEN
            fail(p_name || ' ya existe en la tabla ' || v_table);
        END IF;
        SELECT constraint_type, search_condition_vc, r_constraint_name, status, validated
        INTO v_kind, v_condition, v_referenced, v_status, v_validated
        FROM user_constraints WHERE constraint_name = p_name;
        IF v_kind <> p_type
                OR NVL(columns_of(p_name), '-') <> p_columns
                OR (p_condition IS NOT NULL AND NVL(norm(v_condition), '-') <> norm(p_condition))
                OR (p_referenced IS NOT NULL AND NVL(v_referenced, '-') <> p_referenced)
                OR v_status <> 'ENABLED'
                OR v_validated <> 'VALIDATED' THEN
            fail(p_name || ' existe con otra definicion: tipo=' || v_kind || ' columnas=' || columns_of(p_name)
                    || ' condicion=' || v_condition || ' referencia=' || v_referenced
                    || ' estado=' || v_status || '/' || v_validated);
        END IF;
        DBMS_OUTPUT.PUT_LINE('Verificada: ' || p_name);
    END;
BEGIN
    -- 0. Ningún nombre del script puede pertenecer a otra tabla. Se revisa antes de cualquier cambio.
    FOR c IN (
        SELECT constraint_name, table_name FROM user_constraints
        WHERE (constraint_name IN ('CK_REQUEST_ACTIVITY_KIND', 'CK_REQUEST_ACTIVITY_HAS_ATTACHMENT',
                                   'UK_REQUEST_ACTIVITY_REQUEST')
               AND table_name <> 'REQUEST_ACTIVITY')
           OR (constraint_name IN ('PK_REQUEST_ACTIVITY_ATTACHMENTS', 'CK_REQUEST_ACTIVITY_ATTACHMENT_TYPE',
                                   'FK_REQUEST_ACTIVITY_ATTACHMENT_COMMENT')
               AND table_name <> 'REQUEST_ACTIVITY_ATTACHMENTS')
    ) LOOP
        fail(c.constraint_name || ' ya existe en la tabla ' || c.table_name);
    END LOOP;
    FOR c IN (
        SELECT index_name, table_name FROM user_indexes
        WHERE index_name IN ('UK_REQUEST_ACTIVITY_REQUEST', 'PK_REQUEST_ACTIVITY_ATTACHMENTS')
          AND table_name NOT IN ('REQUEST_ACTIVITY', 'REQUEST_ACTIVITY_ATTACHMENTS')
    ) LOOP
        fail('El indice ' || c.index_name || ' ya existe en la tabla ' || c.table_name);
    END LOOP;

    -- 1. CHECK de kind con COMENTARIO; sin él, el primer comentario falla con ORA-02290. Se reemplazan el que lleva
    --    el nombre del script, escrito como sea, y el que Hibernate creó con nombre SYS_C.
    SELECT constraint_name BULK COLLECT INTO v_names FROM user_constraints
    WHERE table_name = 'REQUEST_ACTIVITY'
      AND constraint_type = 'C'
      AND (constraint_name = 'CK_REQUEST_ACTIVITY_KIND'
           OR REGEXP_LIKE(search_condition_vc, '^[[:space:]]*"?KIND"?[[:space:]]+IN[[:space:]]*\(', 'i'));
    FOR i IN 1 .. v_names.COUNT LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE request_activity DROP CONSTRAINT "' || v_names(i) || '"';
        DBMS_OUTPUT.PUT_LINE('Reemplazado: CHECK de kind ' || v_names(i));
    END LOOP;
    ensure('CK_REQUEST_ACTIVITY_KIND', 'REQUEST_ACTIVITY', 'C', 'KIND',
           q'[kind IN ('CREADO', 'ESTADO', 'GESTION', 'ASIGNACION', 'CALIFICACION', 'COMENTARIO')]', NULL,
           q'[ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_kind
                  CHECK (kind IN ('CREADO', 'ESTADO', 'GESTION', 'ASIGNACION', 'CALIFICACION', 'COMENTARIO'))]');

    -- 2. has_attachment. Con DEFAULT, Oracle completa las filas existentes sin reescribir la tabla.
    SELECT COUNT(*) INTO v_count FROM user_tab_columns
    WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'HAS_ATTACHMENT';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE request_activity ADD (has_attachment NUMBER(1) DEFAULT 0 NOT NULL)';
        DBMS_OUTPUT.PUT_LINE('Creada: request_activity.has_attachment');
    ELSE
        SELECT data_type, data_precision, nullable INTO v_type, v_precision, v_nullable FROM user_tab_columns
        WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'HAS_ATTACHMENT';
        IF v_type <> 'NUMBER' OR NVL(v_precision, -1) <> 1 THEN
            fail('request_activity.has_attachment existe como ' || v_type || '(' || v_precision || ')');
        END IF;
        EXECUTE IMMEDIATE 'ALTER TABLE request_activity MODIFY (has_attachment DEFAULT 0)';
        IF v_nullable = 'Y' THEN
            -- Una fila sin valor es anterior a EP3-64: no tiene foto.
            EXECUTE IMMEDIATE 'UPDATE request_activity SET has_attachment = 0 WHERE has_attachment IS NULL';
            COMMIT;
            EXECUTE IMMEDIATE 'ALTER TABLE request_activity MODIFY (has_attachment NOT NULL)';
        END IF;
        DBMS_OUTPUT.PUT_LINE('Verificada: request_activity.has_attachment');
    END IF;
    ensure('CK_REQUEST_ACTIVITY_HAS_ATTACHMENT', 'REQUEST_ACTIVITY', 'C', 'HAS_ATTACHMENT',
           'has_attachment IN (0, 1)', NULL,
           'ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_has_attachment'
               || ' CHECK (has_attachment IN (0, 1))');

    -- 3. UNIQUE (id, request_id). id ya es PK: existe solo para que la FK compuesta la referencie.
    ensure('UK_REQUEST_ACTIVITY_REQUEST', 'REQUEST_ACTIVITY', 'U', 'ID,REQUEST_ID', NULL, NULL,
           'ALTER TABLE request_activity ADD CONSTRAINT uk_request_activity_request UNIQUE (id, request_id)');

    -- 4. Tabla de fotos. Si requests se desplegó antes que el script, Hibernate ya la creó sin la FK:
    --    se completan sus restricciones sin tocar las filas.
    SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'REQUEST_ACTIVITY_ATTACHMENTS';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE q'[
            CREATE TABLE request_activity_attachments (
                activity_id     VARCHAR2(36)  NOT NULL,
                request_id      VARCHAR2(36)  NOT NULL,
                attachment_data BLOB          NOT NULL,
                content_type    VARCHAR2(30)  NOT NULL,
                file_name       VARCHAR2(40)  NOT NULL,
                created_at      TIMESTAMP WITH TIME ZONE NOT NULL
            )]';
        DBMS_OUTPUT.PUT_LINE('Creada: request_activity_attachments');
    ELSE
        SELECT COUNT(*) INTO v_count FROM user_tab_columns
        WHERE table_name = 'REQUEST_ACTIVITY_ATTACHMENTS'
          AND column_name IN ('ACTIVITY_ID', 'REQUEST_ID', 'ATTACHMENT_DATA', 'CONTENT_TYPE', 'FILE_NAME', 'CREATED_AT');
        IF v_count <> 6 THEN
            fail('request_activity_attachments existe sin las seis columnas esperadas');
        END IF;
        -- La PK que crea Hibernate lleva nombre de sistema: se renombra si cubre solo activity_id.
        SELECT constraint_name BULK COLLECT INTO v_names FROM user_constraints
        WHERE table_name = 'REQUEST_ACTIVITY_ATTACHMENTS'
          AND constraint_type = 'P'
          AND constraint_name <> 'PK_REQUEST_ACTIVITY_ATTACHMENTS';
        FOR i IN 1 .. v_names.COUNT LOOP
            IF columns_of(v_names(i)) <> 'ACTIVITY_ID' THEN
                fail('request_activity_attachments tiene la PK ' || v_names(i) || ' sobre ' || columns_of(v_names(i)));
            END IF;
            EXECUTE IMMEDIATE 'ALTER TABLE request_activity_attachments RENAME CONSTRAINT "' || v_names(i)
                    || '" TO pk_request_activity_attachments';
            DBMS_OUTPUT.PUT_LINE('Renombrada: PK ' || v_names(i));
        END LOOP;
    END IF;
    ensure('PK_REQUEST_ACTIVITY_ATTACHMENTS', 'REQUEST_ACTIVITY_ATTACHMENTS', 'P', 'ACTIVITY_ID', NULL, NULL,
           'ALTER TABLE request_activity_attachments ADD CONSTRAINT pk_request_activity_attachments'
               || ' PRIMARY KEY (activity_id)');
    ensure('CK_REQUEST_ACTIVITY_ATTACHMENT_TYPE', 'REQUEST_ACTIVITY_ATTACHMENTS', 'C', 'CONTENT_TYPE',
           q'[content_type IN ('image/jpeg', 'image/png')]', NULL,
           q'[ALTER TABLE request_activity_attachments ADD CONSTRAINT ck_request_activity_attachment_type
                  CHECK (content_type IN ('image/jpeg', 'image/png'))]');
    -- Impide una imagen con un trámite distinto al de su comentario.
    ensure('FK_REQUEST_ACTIVITY_ATTACHMENT_COMMENT', 'REQUEST_ACTIVITY_ATTACHMENTS', 'R', 'ACTIVITY_ID,REQUEST_ID',
           NULL, 'UK_REQUEST_ACTIVITY_REQUEST',
           'ALTER TABLE request_activity_attachments ADD CONSTRAINT fk_request_activity_attachment_comment'
               || ' FOREIGN KEY (activity_id, request_id) REFERENCES request_activity (id, request_id)');
END;
/

-- Comprobación: seis restricciones ENABLED y VALIDATED, has_attachment NUMBER(1) NOT NULL con default 0,
-- y un solo CHECK de kind en request_activity.
SELECT table_name, constraint_name, constraint_type, status, validated, r_constraint_name, search_condition_vc
FROM user_constraints
WHERE constraint_name IN (
    'CK_REQUEST_ACTIVITY_KIND', 'CK_REQUEST_ACTIVITY_HAS_ATTACHMENT', 'UK_REQUEST_ACTIVITY_REQUEST',
    'PK_REQUEST_ACTIVITY_ATTACHMENTS', 'CK_REQUEST_ACTIVITY_ATTACHMENT_TYPE', 'FK_REQUEST_ACTIVITY_ATTACHMENT_COMMENT')
ORDER BY table_name, constraint_name;
SELECT constraint_name, search_condition_vc FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY'
  AND constraint_type = 'C'
  AND REGEXP_LIKE(search_condition_vc, '^[[:space:]]*"?KIND"?[[:space:]]+IN[[:space:]]*\(', 'i');
SELECT column_name, data_type, data_precision, nullable, data_default FROM user_tab_columns
WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'HAS_ATTACHMENT';
