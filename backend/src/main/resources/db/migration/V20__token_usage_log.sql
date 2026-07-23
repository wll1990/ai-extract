CREATE TABLE token_usage_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID,
    usage_date DATE NOT NULL,
    model_type VARCHAR(20) NOT NULL,
    model_name VARCHAR(100),
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_token_user_date ON token_usage_log(user_id, usage_date);
CREATE INDEX idx_token_date_model ON token_usage_log(usage_date, model_type);
