CREATE TABLE partner_app (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id VARCHAR(50) NOT NULL UNIQUE,
    app_name VARCHAR(100) NOT NULL,
    secret_key VARCHAR(500) NOT NULL,
    old_secret_key VARCHAR(500),
    old_key_expires_at TIMESTAMP,
    status VARCHAR(10) NOT NULL DEFAULT 'ENABLED',
    contact_name VARCHAR(50),
    contact_email VARCHAR(100),
    created_by UUID,
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);
