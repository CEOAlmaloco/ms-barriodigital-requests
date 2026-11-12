-- =====================================================================================
-- EP3-74: encuesta de satisfacción.
-- Oracle Autonomous. Corre UNA vez, antes de que un titular califique un trámite.
--
-- ddl-auto: update agrega satisfaction_score y satisfaction_comment en municipal_requests.
-- No cambia el CHECK de kind: hay que ampliarlo para CALIFICACION. Sin este paso, la
-- primera calificación falla con ORA-02290.
-- Una base nueva no lo necesita si Hibernate crea la tabla con el CHECK de schema.sql.
-- =====================================================================================

WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa del CHECK de kind actual.
SELECT constraint_name, search_condition_vc
FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY'
  AND constraint_type = 'C'
  AND UPPER(search_condition_vc) LIKE 'KIND IN (%';

BEGIN
    FOR c IN (
        SELECT constraint_name
        FROM user_constraints
        WHERE table_name = 'REQUEST_ACTIVITY'
          AND constraint_type = 'C'
          AND UPPER(search_condition_vc) LIKE 'KIND IN (%'
    ) LOOP
        EXECUTE IMMEDIATE 'ALTER TABLE request_activity DROP CONSTRAINT ' || c.constraint_name;
    END LOOP;
END;
/

ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_kind
    CHECK (kind IN ('CREADO', 'ESTADO', 'GESTION', 'ASIGNACION', 'CALIFICACION'));

SELECT constraint_name, search_condition_vc FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY'
  AND constraint_name = 'CK_REQUEST_ACTIVITY_KIND';
