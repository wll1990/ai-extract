-- 补充 skill_material 状态：parsed（解析完成）、parse_failed（解析失败）、cleaning_failed（清洗失败）
ALTER TABLE public.skill_material DROP CONSTRAINT skill_material_status_check;
ALTER TABLE public.skill_material ADD CONSTRAINT skill_material_status_check CHECK (
    (status)::text = ANY (ARRAY[
        'uploaded', 'parse_failed',
        'parsed',
        'cleaning', 'cleaning_failed',
        'analyzed', 'analyzing',
        'extracted',
        'discarded', 'failed', 'rejected'
    ]::text[])
);
