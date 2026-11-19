-- =====================================================================================
-- EP3-76: estado CANCELADO en los CHECK de status / from_status / to_status.
-- Oracle Autonomous. Corre UNA vez, antes de que un titular cancele un trámite.
--
-- ddl-auto: update no amplía un CHECK que Hibernate creó al nacer la tabla. Sin este
-- script, el primer POST /cancel falla con ORA-02290 (SYS_C… sobre to_status o status)
-- y Spring puede devolver el SQL al cliente si include-message=always.
-- Una base nueva creada con este CHECK ya incluido no necesita el script.
-- =====================================================================================

WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa de los CHECK a reemplazar (nombres SYS_C… cambian por base).
SELECT table_name, constraint_name, search_condition_vc
FROM user_constraints
WHERE constraint_type = 'C'
  AND (
        (table_name = 'MUNICIPAL_REQUESTS'
         AND REGEXP_LIKE(search_condition_vc, 'status[[:space:]]+in[[:space:]]*\(', 'i'))
     OR (table_name = 'REQUEST_ACTIVITY'
         AND REGEXP_LIKE(search_condition_vc,
                         '(from_status|to_status)[[:space:]]+in[[:space:]]*\(', 'i'))
      );

DECLARE
    PROCEDURE drop_matching(p_table VARCHAR2, p_pattern VARCHAR2) IS
    BEGIN
        FOR c IN (
            SELECT constraint_name
            FROM user_constraints
            WHERE table_name = p_table
              AND constraint_type = 'C'
              AND REGEXP_LIKE(search_condition_vc, p_pattern, 'i')
        ) LOOP
            EXECUTE IMMEDIATE 'ALTER TABLE ' || p_table || ' DROP CONSTRAINT ' || c.constraint_name;
            DBMS_OUTPUT.PUT_LINE('Reemplazado: ' || p_table || '.' || c.constraint_name);
        END LOOP;
    END;
BEGIN
    drop_matching('MUNICIPAL_REQUESTS', 'status[[:space:]]+in[[:space:]]*\(');
    drop_matching('REQUEST_ACTIVITY', 'from_status[[:space:]]+in[[:space:]]*\(');
    drop_matching('REQUEST_ACTIVITY', 'to_status[[:space:]]+in[[:space:]]*\(');
END;
/

ALTER TABLE municipal_requests ADD CONSTRAINT ck_municipal_requests_status
    CHECK (status IN (
        'INGRESADO', 'ADMITIDO', 'EN_GESTION', 'EN_TERRENO', 'RESUELTO', 'RECHAZADO', 'CANCELADO'
    ));

ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_from_status
    CHECK (from_status IS NULL OR from_status IN (
        'INGRESADO', 'ADMITIDO', 'EN_GESTION', 'EN_TERRENO', 'RESUELTO', 'RECHAZADO', 'CANCELADO'
    ));

ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_to_status
    CHECK (to_status IS NULL OR to_status IN (
        'INGRESADO', 'ADMITIDO', 'EN_GESTION', 'EN_TERRENO', 'RESUELTO', 'RECHAZADO', 'CANCELADO'
    ));

SELECT table_name, constraint_name, search_condition_vc
FROM user_constraints
WHERE constraint_name IN (
    'CK_MUNICIPAL_REQUESTS_STATUS',
    'CK_REQUEST_ACTIVITY_FROM_STATUS',
    'CK_REQUEST_ACTIVITY_TO_STATUS'
)
ORDER BY constraint_name;
