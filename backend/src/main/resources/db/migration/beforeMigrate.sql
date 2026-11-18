-- 每次 migrate 前执行（Flyway callback，不记入版本历史）。
-- V59 起 search_text 使用 zhparser_cfg 生成 tsvector；空库必须先创建该配置，
-- 否则 V59 会以 "text search configuration \"zhparser_cfg\" does not exist" 失败。
-- 幂等：已有配置时直接跳过。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'zhparser_cfg') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS zhparser;
            CREATE TEXT SEARCH CONFIGURATION zhparser_cfg (PARSER = zhparser);
            ALTER TEXT SEARCH CONFIGURATION zhparser_cfg
                ADD MAPPING FOR n,v,a,i,e,l WITH simple;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE EXCEPTION '需要超级用户先执行 CREATE EXTENSION zhparser 并创建 zhparser_cfg';
        END;
    END IF;
END $$;
