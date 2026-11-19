-- =====================================================================================
-- EP3-28: folio para los trámites existentes (contrato EP3-05, sección 1.4).
-- Oracle Autonomous. Corre UNA vez, en una ventana de mantenimiento.
--
-- Paso 0, antes de este script: DETENER requests. La versión anterior no conoce folio y,
-- si sigue creando trámites, el NOT NULL hace fallar cada alta o deja trámites sin folio.
-- Paso 5, después de este script: desplegar EP3-28 y levantar requests.
--
-- Los tipos calzan con las entidades MunicipalRequest y FolioCounter, para que
-- ddl-auto: update no tenga nada que agregar al arrancar.
-- =====================================================================================

-- Detiene el script ante el primer error, en SQLcl, SQL*Plus y el worksheet de SQL Developer.
WHENEVER SQLERROR EXIT ROLLBACK

-- Vista previa, solo lectura: el folio que recibirá cada trámite. Se puede correr sola antes de todo el script.
SELECT id,
       created_at,
       'BD-' || TO_CHAR(folio_year, 'FM0000') || '-'
           || TO_CHAR(ROW_NUMBER() OVER (PARTITION BY folio_year ORDER BY created_at, id), 'FM000000') AS folio_previsto
FROM (
    SELECT id,
           created_at,
           EXTRACT(YEAR FROM (created_at AT TIME ZONE 'America/Santiago')) AS folio_year
    FROM municipal_requests
)
ORDER BY folio_year, created_at, id;

-- 1. Columna folio, todavía nullable, y tabla del contador.
ALTER TABLE municipal_requests ADD (folio VARCHAR2(14 CHAR));

CREATE TABLE folio_counter (
    folio_year  NUMBER(4) NOT NULL,
    last_number NUMBER    NOT NULL,
    CONSTRAINT pk_folio_counter PRIMARY KEY (folio_year)
);

-- 2. Numeración por año en hora de Chile, en orden de creación. id desempata los trámites con el mismo
--    created_at. created_at es TIMESTAMP WITH TIME ZONE: AT TIME ZONE convierte desde el offset guardado.
MERGE INTO municipal_requests t
USING (
    SELECT id,
           folio_year,
           ROW_NUMBER() OVER (PARTITION BY folio_year ORDER BY created_at, id) AS folio_number
    FROM (
        SELECT id,
               created_at,
               EXTRACT(YEAR FROM (created_at AT TIME ZONE 'America/Santiago')) AS folio_year
        FROM municipal_requests
    )
) s
ON (t.id = s.id)
WHEN MATCHED THEN UPDATE
    SET t.folio = 'BD-' || TO_CHAR(s.folio_year, 'FM0000') || '-' || TO_CHAR(s.folio_number, 'FM000000');

-- 3. Ningún trámite sin folio y ningún folio repetido. ddl-auto: update no agrega un NOT NULL
--    sobre filas existentes, por eso va aquí. El nombre del constraint es el de la entidad.
ALTER TABLE municipal_requests MODIFY (folio NOT NULL);
ALTER TABLE municipal_requests ADD CONSTRAINT uk_municipal_requests_folio UNIQUE (folio);

-- 4. Contador: el máximo de cada año, más el año en curso y el siguiente en Chile si faltan.
INSERT INTO folio_counter (folio_year, last_number)
SELECT CAST(SUBSTR(folio, 4, 4) AS NUMBER(4)), MAX(CAST(SUBSTR(folio, 9, 6) AS NUMBER))
FROM municipal_requests
GROUP BY CAST(SUBSTR(folio, 4, 4) AS NUMBER(4));

MERGE INTO folio_counter c
USING (
    SELECT EXTRACT(YEAR FROM (SYSTIMESTAMP AT TIME ZONE 'America/Santiago')) + offset_years AS folio_year
    FROM (SELECT 0 AS offset_years FROM dual UNION ALL SELECT 1 FROM dual)
) s
ON (c.folio_year = s.folio_year)
WHEN NOT MATCHED THEN INSERT (folio_year, last_number) VALUES (s.folio_year, 0);

COMMIT;

-- Comprobación: debe dar 0 trámites sin folio y el último número de cada año.
SELECT COUNT(*) AS sin_folio FROM municipal_requests WHERE folio IS NULL;
SELECT folio_year, last_number FROM folio_counter ORDER BY folio_year;
