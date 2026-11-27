CREATE TABLE idempotency_keys (
                                  id BIGSERIAL PRIMARY KEY,
                                  idempotency_key VARCHAR(128) NOT NULL,
                                  request_hash VARCHAR(64) NOT NULL,
                                  estado VARCHAR(20) NOT NULL,
                                  status_code INT,
                                  response_body TEXT,
                                  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
                                  CONSTRAINT chk_idempotency_estado CHECK (estado IN ('EN_PROCESO', 'COMPLETADA', 'FALLIDA'))
);