/**
 * 产品导览类型与常量。
 *
 * 约束：
 * - 本文件与 chapters.ts 必须保持纯数据，不 import React / next / api，
 *   否则 Playwright 在 Node 环境直接 import 时会炸。
 * - 所有 data-tour 名称集中在这里，页面与章节共用，避免字符串漂移。
 */

export type TrackId = 'sales' | 'onboarding' | 'employee';
export type StepKind = 'spotlight' | 'concept';
export type InteractionMode = 'inspect' | 'modal';
export type Placement = 'top' | 'bottom' | 'left' | 'right' | 'center';

export type DataFlag =
  | 'publishedSkill'
  | 'reviewingSkill'
  | 'skillWithMaterials'
  | 'reportReadyReview'
  | 'openGap'
  | 'knowledge'
  | 'cases'
  | 'trainingPlan'
  | 'evalRun';

export type StepAction =
  | { type: 'click'; target: string }
  | { type: 'set-view'; view: string; sub?: string };

export interface TourStep {
  id: string;
  kind: StepKind;
  /** inspect：页面可操作；modal：锁定背景（仅概念卡使用） */
  interaction?: InteractionMode;
  /** 支持章内跨路由，可用 {publishedSkillId} 等占位符 */
  route?: string;
  /** data-tour 值 */
  target?: string;
  /** 目标过大时优先高亮子区域 */
  focusTarget?: string;
  actions?: StepAction[];
  requires?: DataFlag;
  requiredPermissions?: string[];
  title: string;
  body: string;
  bullets?: string[];
  placement?: Placement;
  /** 缺元素时：block 停下说明，skip 自动跳过 */
  policy?: 'skip' | 'block';
  estimatedSeconds: number;
  /** 文案依据（文件:行），供审稿；不渲染 */
  source: string;
}

export interface TourChapter {
  id: string;
  tracks: TrackId[];
  title: string;
  summary: string;
  permission?: string;
  /** 未关闭的缺陷编号；任一存在则本章不出现 */
  blockedBy?: string[];
  steps: TourStep[];
}

export interface TourData {
  publishedSkillId?: string;
  reviewingSkillId?: string;
  skillWithMaterialsId?: string;
  reportReadyReviewId?: string;
  reportReadyReview?: boolean;
  openGapSkillId?: string;
  openGapId?: string;
  openGap?: boolean;
  hasKnowledge?: boolean;
  hasCases?: boolean;
  trainingPlanId?: string;
  evalRunId?: string;
}

/** 演示企业：售前投屏只允许在此企业启动 */
export const DEMO_COMPANY_ID = 'd0000000-0000-0000-0000-000000000001';

/** 当前导览版本；章节文案变更时递增，旧进度自动失效 */
export const TOUR_VERSION = 2;

/** 页面锚点常量 */
export const ANCHORS = {
  adminTitle: 'admin-title',
  adminKpi: 'admin-kpi',
  adminPending: 'admin-pending',
  skillsList: 'skills-list',
  skillsTabs: 'skills-tabs',
  skillModeTabs: 'skill-mode-tabs',
  skillTrace: 'skill-trace',
  skillThread: 'skill-thread',
  skillInput: 'skill-input',
  interviewCta: 'interview-create-cta',
  uploadTabs: 'upload-tabs',
  uploadForm: 'upload-form',
  uploadGuide: 'upload-guide',
  materialsTable: 'materials-table',
  auditSteps: 'audit-step-tabs',
  auditPublish: 'audit-publish-check',
  reviewNew: 'review-new-cta',
  reviewReport: 'review-report',
  reviewActions: 'review-actions',
  gapPanel: 'gap-panel',
  grainForm: 'grain-form',
  knowledgeTabs: 'knowledge-tabs',
  knowledgeAdd: 'knowledge-add',
  casesAdd: 'cases-add',
  trainingTabs: 'training-tabs',
  trainingPlanList: 'training-plan-list',
  userCreate: 'user-create',
  shareEntry: 'admin-skills-share',
  coachLedger: 'coach-ledger',
  evalDatasets: 'eval-datasets',
  evalRuns: 'eval-runs',
  imBots: 'im-bots',
  insightsOverview: 'insights-overview',
  insightsSkill: 'insights-skill',
} as const;

export type AnchorKey = keyof typeof ANCHORS;
export type AnchorValue = (typeof ANCHORS)[AnchorKey];
