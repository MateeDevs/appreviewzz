-- db-scheduler ([ADR 0004](../../../../../../docs/adr/0004-db-scheduler.md)) drží frontu úloh
-- v téže databázi jako doménová data. Schéma je dané knihovnou — názvy sloupců se nesmí měnit,
-- proto se tady jako jediné v projektu nedrží naší konvence a nemá triggery ani FK.
--
-- Jde o oficiální PostgreSQL schéma pro db-scheduler 16.x. `priority` používáme jen pokud
-- se zapne v konfiguraci, sloupec ale musí existovat vždy, jinak knihovna neumí ani INSERT.

CREATE TABLE scheduled_tasks (
    task_name            text        NOT NULL,
    task_instance        text        NOT NULL,
    task_data            bytea,
    execution_time       timestamptz NOT NULL,
    picked               boolean     NOT NULL,
    picked_by            text,
    last_success         timestamptz,
    last_failure         timestamptz,
    consecutive_failures integer,
    last_heartbeat       timestamptz,
    version              bigint      NOT NULL,
    priority             smallint,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX scheduled_tasks_execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX scheduled_tasks_last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX scheduled_tasks_priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
