-- Solo tests. El valor automático y la UNIQUE de db/activity-sequence.sql, sin ORDER, que H2 no acepta.
-- Corre después de que Hibernate crea el esquema de H2.
CREATE SEQUENCE IF NOT EXISTS request_activity_seq;

ALTER TABLE request_activity ALTER COLUMN activity_sequence SET DEFAULT NEXT VALUE FOR request_activity_seq;

ALTER TABLE request_activity ADD CONSTRAINT IF NOT EXISTS uk_request_activity_sequence UNIQUE (activity_sequence);
