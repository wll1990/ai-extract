/**
 * 销冠门户 · 试点数据配置位。
 *
 * 现在还没有可公开的试点数据，所以这里是空数组——页面会自动把指标区
 * 换成「现场验证」区块：不给客户看截图数字，而是当场用他自己的场景跑一遍。
 *
 * 试点跑出真实数据后，按下面格式填入即可，页面自动切换成「试点数据」展示，
 * 不需要改任何组件：
 *
 *   export const PILOT_METRICS: PilotMetric[] = [
 *     { value: '12', label: '试点团队', note: '覆盖 3 个行业' },
 *     { value: '3,480', label: '已回答问题', note: '含引用回答 92%' },
 *   ];
 *
 * 规则：只填能从后端统计口径直接查出来的数字，不做估算、不做加权修饰。
 */

export interface PilotMetric {
  value: string;
  label: string;
  note: string;
}

export const PILOT_METRICS: PilotMetric[] = [];

/** 指标口径说明。有数据时与指标一起展示，避免被误读。 */
export const PILOT_METRICS_NOTE = '统计口径：试点团队内全部已发布分身的问答与复盘记录，按自然月去重。';
