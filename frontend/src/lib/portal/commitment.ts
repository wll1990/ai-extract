/**
 * 试点承诺（商务口径，开关默认关闭）。
 *
 * 顶级 B2B 官网常用「风险反转」替代社会证明：不达标你可以不付。
 * 但这属于商务条款，不能由工程侧单方面写死上线。
 * 确认口径后把 enabled 改成 true 即可，页面无需改动。
 */

export const PILOT_COMMITMENT = {
  enabled: false,
  title: '30 天试点，跑不通就停。',
  detail: '不收试点的钱，也不要求签年框。',
};
