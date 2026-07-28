ALTER TABLE token_usage_log ADD COLUMN IF NOT EXISTS prompt_chars INT DEFAULT 0;
ALTER TABLE token_usage_log ADD COLUMN IF NOT EXISTS completion_chars INT DEFAULT 0;
