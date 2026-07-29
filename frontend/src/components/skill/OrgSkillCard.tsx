'use client';
import { StatBadge } from '@aiextract/shared-ui';
import type { SkillInfo } from '@/lib/api/skill';

const AVATAR_COLORS = ['#6366f1', '#8b5cf6', '#a855f7', '#d946ef', '#ec4899'];

export function OrgSkillCard({ skill }: { skill: SkillInfo }) {
  const memberCount = skill.memberCount || 0;
  const memberAvatars = (skill.members || []).slice(0, 4);
  const remaining = Math.max(0, memberCount - 4);

  return (
    <div className="rounded-2xl bg-surface-2 p-6 text-left shadow-sm hover:shadow-md transition-all hover:-translate-y-0.5 w-full">
      {/* Member avatars row */}
      <div className="flex items-center gap-1 mb-3">
        {memberAvatars.map((m, i) => (
          <div key={m.id} className="relative" style={{ marginLeft: i > 0 ? -8 : 0, zIndex: 4 - i }}>
            {m.avatarUrl ? (
              <img src={m.avatarUrl} alt={m.ownerName}
                className="w-10 h-10 rounded-full border-2 border-white object-cover" />
            ) : (
              <div className="w-10 h-10 rounded-full border-2 border-white flex items-center justify-center text-white text-sm font-bold"
                style={{ background: AVATAR_COLORS[i % AVATAR_COLORS.length] }}>
                {(m.ownerName || '?')[0]}
              </div>
            )}
          </div>
        ))}
        {remaining > 0 && (
          <div className="w-10 h-10 rounded-full border-2 border-white bg-gray-200 flex items-center justify-center text-xs text-gray-500 font-medium"
            style={{ marginLeft: -8 }}>
            +{remaining}
          </div>
        )}
        {memberCount === 0 && (
          <div className="w-10 h-10 rounded-full border-2 border-white bg-gray-100 flex items-center justify-center text-gray-400 text-xl">
            🏢
          </div>
        )}
      </div>

      {/* Name & description */}
      <h3 className="font-semibold text-foreground truncate">{skill.displayName || skill.ownerName}</h3>
      <p className="text-xs text-muted-foreground-2 mt-0.5 mb-2">
        {skill.ownerTitle || `综合 ${memberCount} 位销冠`}
      </p>

      {/* Stats */}
      {skill.stats && (skill.stats.conversationCount > 0 || skill.stats.satisfactionRate > 0) && (
        <div className="flex items-center gap-2 rounded-lg px-2 py-1 mb-2" style={{ background: '#f8faff' }}>
          {skill.stats.conversationCount > 0 && (
            <StatBadge icon="💬" value={skill.stats.conversationCount} label="次" size="sm" />
          )}
          {skill.stats.satisfactionRate > 0 && (
            <><span className="text-[#d4d8e0] text-xs">·</span>
            <StatBadge icon="👍" value={skill.stats.satisfactionRate} label="%" size="sm" /></>
          )}
          {skill.stats.userCount > 0 && (
            <><span className="text-[#d4d8e0] text-xs">·</span>
            <StatBadge icon="👤" value={skill.stats.userCount} label="人" size="sm" /></>
          )}
        </div>
      )}

      {/* Domain badge */}
      {skill.domain && (
        <span className="inline-block rounded-full bg-[#eef2ff] px-2 py-0.5 text-[10px] text-[#475569]">
          {skill.domain === 'sales' ? 'B2B销售' : skill.domain}
        </span>
      )}
    </div>
  );
}
