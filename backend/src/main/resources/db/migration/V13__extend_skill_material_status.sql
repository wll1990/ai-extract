-- V12__extend_skill_material_status.sql
-- 补齐 skill_material.status 约束缺失的两个实际在用状态：
--   failed   — 访谈转录清洗失败（InterviewTranscriptExtractor catch 块）
--   rejected — 准入检查/重复检测不通过（MaterialCleaningService）
-- 存量数据满足旧集合 ⊂ 新集合，ADD CONSTRAINT 全表校验必过，可安全上线。

ALTER TABLE skill_material DROP CONSTRAINT IF EXISTS skill_material_status_check;

ALTER TABLE skill_material ADD CONSTRAINT skill_material_status_check
    CHECK (status IN ('uploaded','cleaning','cleaned','analyzing','analyzed',
                      'extracted','discarded','failed','rejected'));

COMMENT ON COLUMN skill_material.status IS
    '状态: uploaded→cleaning→analyzing→analyzed→extracted; rejected=准入不通过; failed=访谈转录清洗失败; discarded=已废弃';
