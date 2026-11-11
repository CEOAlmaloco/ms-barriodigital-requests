-- =====================================================================================
-- EP3-29: historial de asignación en request_activity (contrato EP3-05, sección 6.4).
-- Oracle Autonomous. Corre UNA vez, antes de que alguien tome o asigne un trámite.
--
-- ddl-auto: update agrega solo las columnas nuevas (assignee_*, previous_assignee_* e intervention_reason).
-- No relaja un NOT NULL ni cambia un CHECK que ya existe, así que este script hace las dos cosas:
--   1. actor_id pasa a nullable, solo para el hito público de asignación, que no tiene actor;
--   2. el CHECK de kind que Hibernate creó con CREADO, ESTADO y GESTION se reemplaza por uno que
--      también acepta ASIGNACION. Sin este paso, la primera toma falla con ORA-02290.
-- Una base nueva no lo necesita: Hibernate crea la tabla con las cuatro marcas y con el CHECK del actor.
-- =====================================================================================

-- Detiene el script ante el primer error, en SQLcl, SQL*Plus y el worksheet de SQL Developer.
WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: el CHECK de kind que se va a reemplazar. Su nombre (SYS_C...) cambia en cada base.
SELECT constraint_name, search_condition_vc
FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY'
  AND constraint_type = 'C'
  AND UPPER(search_condition_vc) LIKE 'KIND IN (%';

-- 1. actor_id nullable.
ALTER TABLE request_activity MODIFY (actor_id NULL);

-- 2. Reemplaza el CHECK de kind. Se busca por su condición, en SEARCH_CONDITION_VC (SEARCH_CONDITION es LONG
--    y no admite LIKE).
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
    CHECK (kind IN ('CREADO', 'ESTADO', 'GESTION', 'ASIGNACION'));

-- 3. Toda acción humana tiene autor: solo el hito público de asignación va sin actor.
--    El nombre es el del @Check de la entidad RequestActivity.
ALTER TABLE request_activity ADD CONSTRAINT ck_request_activity_actor
    CHECK (actor_id IS NOT NULL OR (kind = 'ASIGNACION' AND visibility = 'PUBLICO'));

-- Comprobación: actor_id nullable y los dos CHECK nuevos.
SELECT column_name, nullable FROM user_tab_columns
WHERE table_name = 'REQUEST_ACTIVITY' AND column_name = 'ACTOR_ID';
SELECT constraint_name, search_condition_vc FROM user_constraints
WHERE table_name = 'REQUEST_ACTIVITY'
  AND constraint_name IN ('CK_REQUEST_ACTIVITY_KIND', 'CK_REQUEST_ACTIVITY_ACTOR');
