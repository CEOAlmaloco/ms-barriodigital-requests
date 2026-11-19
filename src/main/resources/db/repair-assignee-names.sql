-- =====================================================================================
-- Reparación de nombres de responsable guardados como oid. Oracle Autonomous.
--
-- Antes del fix, una llamada de staff sin X-User-Name (las lecturas del BFF) dejaba el oid como nombre en
-- staff_member, y asignar copiaba ese nombre a los snapshots. Este script corrige solo los snapshots cuyo nombre
-- es igual al id y cuya fuente en staff_member ya tiene un nombre válido (no vacío y distinto del oid).
-- Un snapshot sin fuente válida no se toca: se repara en otra corrida, después de que esa persona vuelva a entrar.
--
-- No toca outbox_events: los eventos ya emitidos son historia y audit no se reescribe.
-- Se puede correr más de una vez: lo ya reparado deja de cumplir la condición.
-- =====================================================================================

WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: qué se va a reparar y con qué nombre.
SELECT 'municipal_requests.assignee_name' AS columna, r.id AS fila, r.assignee_id AS oid, TRIM(s.name) AS nombre
FROM municipal_requests r
JOIN staff_member s ON s.oid = r.assignee_id
WHERE r.assignee_name = r.assignee_id
  AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid
UNION ALL
SELECT 'request_activity.assignee_name', a.id, a.assignee_id, TRIM(s.name)
FROM request_activity a
JOIN staff_member s ON s.oid = a.assignee_id
WHERE a.assignee_name = a.assignee_id
  AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid
UNION ALL
SELECT 'request_activity.previous_assignee_name', a.id, a.previous_assignee_id, TRIM(s.name)
FROM request_activity a
JOIN staff_member s ON s.oid = a.previous_assignee_id
WHERE a.previous_assignee_name = a.previous_assignee_id
  AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid;

-- Pendientes sin fuente válida: el funcionario todavía no volvió a entrar con su nombre.
SELECT DISTINCT r.assignee_id AS oid_sin_nombre
FROM municipal_requests r
WHERE r.assignee_name = r.assignee_id
  AND NOT EXISTS (SELECT 1 FROM staff_member s
                  WHERE s.oid = r.assignee_id AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid);

UPDATE municipal_requests r
SET r.assignee_name = (SELECT TRIM(s.name) FROM staff_member s WHERE s.oid = r.assignee_id)
WHERE r.assignee_name = r.assignee_id
  AND EXISTS (SELECT 1 FROM staff_member s
              WHERE s.oid = r.assignee_id AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid);

UPDATE request_activity a
SET a.assignee_name = (SELECT TRIM(s.name) FROM staff_member s WHERE s.oid = a.assignee_id)
WHERE a.assignee_name = a.assignee_id
  AND EXISTS (SELECT 1 FROM staff_member s
              WHERE s.oid = a.assignee_id AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid);

UPDATE request_activity a
SET a.previous_assignee_name = (SELECT TRIM(s.name) FROM staff_member s WHERE s.oid = a.previous_assignee_id)
WHERE a.previous_assignee_name = a.previous_assignee_id
  AND EXISTS (SELECT 1 FROM staff_member s
              WHERE s.oid = a.previous_assignee_id AND TRIM(s.name) IS NOT NULL AND TRIM(s.name) <> s.oid);

COMMIT;

-- Comprobación: snapshots que siguen con el oid como nombre (solo los que aún no tienen fuente válida).
SELECT 'municipal_requests' AS tabla, COUNT(*) AS pendientes
FROM municipal_requests WHERE assignee_name = assignee_id
UNION ALL
SELECT 'request_activity', COUNT(*)
FROM request_activity
WHERE assignee_name = assignee_id OR previous_assignee_name = previous_assignee_id;
