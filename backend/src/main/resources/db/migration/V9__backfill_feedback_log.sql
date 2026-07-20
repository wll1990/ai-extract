-- 将已有 helpful_count 迁移到 feedback_log 表

INSERT INTO feedback_log (id, skill_id, grain_id, rating, source, created_at)
SELECT
    gen_random_uuid(),
    g.space_id,
    g.id,
    'up',
    'backfill',
    NOW()
FROM experience_grain g
WHERE g.helpful_count > 0
CROSS JOIN generate_series(1, LEAST(g.helpful_count, 100));

INSERT INTO feedback_log (id, skill_id, grain_id, rating, source, created_at)
SELECT
    gen_random_uuid(),
    g.space_id,
    g.id,
    'down',
    'backfill',
    NOW()
FROM experience_grain g
WHERE g.unhelpful_count > 0
CROSS JOIN generate_series(1, LEAST(g.unhelpful_count, 100));
