'use client';

import type { SkillHealth } from '@/lib/api/admin-insights';

/** 告警 severity 颜色映射 */
const SEVERITY: Record<string, { bar: string; bg: string; text: string }> = {
  '低满意率':  { bar: '#DC2626', bg: '#FEF2F2', text: '#991B1B' },
  '缺口爆发':  { bar: '#DC2626', bg: '#FEF2F2', text: '#991B1B' },
  '不活跃':    { bar: '#D97706', bg: '#FFFBEB', text: '#92400E' },
  '低命中率':  { bar: '#D97706', bg: '#FFFBEB', text: '#92400E' },
};

function alertSeverity(alert: string) {
  for (const [key, val] of Object.entries(SEVERITY)) {
    if (alert.includes(key)) return val;
  }
  return { bar: '#2563EB', bg: '#EFF6FF', text: '#1E40AF' };
}

/**
 * 分身健康度卡片 —— 卡片网格中的单个卡片。
 *
 * 顶部告警色条（仅异常时显示），中间头像+名称+4 个迷你指标，底部查看详情。
 */
export function SkillCard({ skill, onClick }: { skill: SkillHealth; onClick: () => void }) {
  const initial = (skill.name || '?')[0];
  const hasAlerts = skill.alerts.length > 0;
  const topAlert = hasAlerts ? skill.alerts[0] : null;
  const sev = topAlert ? alertSeverity(topAlert) : null;

  return (
    <div
      onClick={onClick}
      className="rounded-[12px] bg-white border border-[#E8ECF1] shadow-[0_1px_2px_rgba(15,23,42,0.06)] overflow-hidden cursor-pointer transition-all duration-150 hover:shadow-[0_8px_20px_rgba(15,23,42,0.08)] hover:-translate-y-[2px]"
    >
      {/* 告警色条 */}
      {hasAlerts && sev && (
        <div
          className="flex items-center gap-2 px-5 py-2.5 text-[12px] font-medium"
          style={{ background: sev.bg, borderBottom: `1px solid ${sev.bar}20` }}
        >
          <span
            className="inline-block w-1.5 h-1.5 rounded-full flex-shrink-0"
            style={{ background: sev.bar }}
          />
          <span style={{ color: sev.text }}>{topAlert}</span>
        </div>
      )}

      {/* 主体 */}
      <div className="p-5">
        {/* 头像 + 名称 */}
        <div className="flex items-center gap-3 mb-4">
          <div
            className="w-10 h-10 rounded-full flex items-center justify-center text-sm font-bold text-white flex-shrink-0 shadow-sm"
            style={{
              background: 'linear-gradient(135deg, #0F172A, #2563EB)',
            }}
          >
            {initial}
          </div>
          <div className="min-w-0">
            <p className="text-[15px] font-semibold text-[#1E293B] truncate">{skill.name}</p>
            {skill.ownerTitle && (
              <p className="text-[12px] text-[#94A3B8] truncate">{skill.ownerTitle}</p>
            )}
          </div>
        </div>

        {/* 4 个迷你指标 */}
        <div
          className="grid gap-y-2.5 gap-x-3 mb-4"
          style={{ gridTemplateColumns: '1fr 1fr' }}
        >
          <MiniMetric label="对话" value={skill.conversations.toLocaleString()} />
          <MiniMetric label="满意率" value={`${skill.satisfactionRate}%`} />
          <MiniMetric label="命中率" value={`${skill.hitRate}%`} />
          <MiniMetric
            label="颗粒"
            value={skill.grainCount.toLocaleString()}
            alert={skill.openGaps >= 10}
          />
        </div>

        {/* 缺口提示 */}
        {skill.openGaps > 0 && (
          <div className="flex items-center gap-1.5 mb-3">
            <span
              className="inline-block w-1.5 h-1.5 rounded-full"
              style={{ background: skill.openGaps >= 10 ? '#DC2626' : '#D97706' }}
            />
            <span className="text-[11px] text-[#64748B]">
              {skill.openGaps} 个待处理缺口
            </span>
          </div>
        )}

        {/* 查看详情 */}
        <div className="flex items-center justify-end">
          <span className="text-[12px] font-medium text-[#2563EB] hover:underline">
            查看详情 →
          </span>
        </div>
      </div>
    </div>
  );
}

function MiniMetric({ label, value, alert }: { label: string; value: string; alert?: boolean }) {
  return (
    <div>
      <p className="text-[11px] text-[#94A3B8] leading-tight">{label}</p>
      <p
        className="text-[16px] font-semibold leading-tight"
        style={{
          color: alert ? '#DC2626' : '#1E293B',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {value}
      </p>
    </div>
  );
}
