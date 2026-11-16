/** 分身状态口径单一来源（列表、分组、徽标共用） */

export const SKILL_STATUS_LABELS: Record<string, string> = {
  generating: '萃取中',
  reviewing: '待审核',
  draft: '草稿',
  published: '已发布',
  discarded: '已驳回',
  paused: '已停用',
};

/** 列表分组 */
export const SKILL_ACTIVE_STATUSES = ['published'];
export const SKILL_PENDING_STATUSES = ['generating', 'reviewing'];
export const SKILL_INACTIVE_STATUSES = ['discarded', 'paused', 'draft'];
