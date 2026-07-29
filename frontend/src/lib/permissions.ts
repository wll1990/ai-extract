/**
 * 权限码常量 — 与后端 {@code Permission.java} 同源。
 *
 * 不使用角色→权限映射表。映射表只有后端一份（RolePermissions.java），
 * 前端通过 /auth/me 的 permissions 字段获取当前用户拥有的权限码列表。
 *
 * @since 2026-07-28
 */
export const Permission = {
  // Platform 级（仅 super_admin）
  COMPANY_MANAGE: 'COMPANY_MANAGE',
  PARTNER_MANAGE: 'PARTNER_MANAGE',
  IM_MANAGE: 'IM_MANAGE',
  TOKEN_VIEW_ALL: 'TOKEN_VIEW_ALL',

  // Company 级（super_admin + company_admin）
  DASHBOARD_VIEW: 'DASHBOARD_VIEW',
  USER_MANAGE: 'USER_MANAGE',
  SKILL_MANAGE: 'SKILL_MANAGE',
  SKILL_TUNING: 'SKILL_TUNING',
  MATERIAL_MANAGE: 'MATERIAL_MANAGE',
  CONVERSATION_VIEW: 'CONVERSATION_VIEW',
  SCENE_COVERAGE: 'SCENE_COVERAGE',
  EXPERT_MANAGE: 'EXPERT_MANAGE',
  GRAIN_MANAGE: 'GRAIN_MANAGE',
  TOKEN_VIEW_COMPANY: 'TOKEN_VIEW_COMPANY',
  ORG_SKILL_MANAGE: 'ORG_SKILL_MANAGE',

  // Basic 级（所有 B 端用户）
  SKILL_USE: 'SKILL_USE',
  SPACE_OWN: 'SPACE_OWN',
} as const;

export type PermissionCode = (typeof Permission)[keyof typeof Permission];
