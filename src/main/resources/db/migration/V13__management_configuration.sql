CREATE TABLE management_configuration (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    config JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only successful operations. Never store submitted values or credentials in history.
CREATE TABLE management_configuration_history (
    revision BIGINT PRIMARY KEY,
    actor VARCHAR(128) NOT NULL,
    changed_fields JSONB NOT NULL,
    result VARCHAR(16) NOT NULL CHECK (result = 'SAVED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
