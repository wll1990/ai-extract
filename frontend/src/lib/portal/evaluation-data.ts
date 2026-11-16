/**
 * 自评测结果 —— 由真实链路跑出来的，不是手工填的。
 *
 * 生成方式：对演示分身 d3000000-…0001 依次发送 24 个问题（12 个该答、12 个不该答），
 * 记录是否召回经验颗粒、是否拒答。重新跑一遍脚本会得到同样的判定口径。
 * date 字段是本次运行日期；数据变化后应重新生成。
 */

export interface EvalRow {
  question: string;
  inScope: boolean;
  grainCount: number;
  sources: number;
  refused: boolean;
  chars: number;
  passed: boolean;
  ms: number;
  answerHead: string;
}

export const EVAL_SUMMARY = {
  "generatedAt": "2026-11-01",
  "total": 24,
  "inScopePass": 11,
  "inScopeTotal": 12,
  "outScopePass": 12,
  "outScopeTotal": 12,
  "avgMs": 1729
} as const;

export const EVAL_ROWS: EvalRow[] = [
  {
    "question": "关键人挺支持，但采购那边一直没动静，怎么推？",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 318,
    "passed": true,
    "ms": 2507,
    "answerHead": "先别把问题归到采购头上。采购停滞，八成是预算所有者内部优先级还没确认，采购只是执行端。 **判断逻辑**：关键人支持≠决"
  },
  {
    "question": "客户拿竞品的报价来压我们的价格，这个怎么接？",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 242,
    "passed": true,
    "ms": 2283,
    "answerHead": "先别急着解释价格构成，那是坑。我会先问一句：“您更在意一次性采购成本，还是三年总拥有成本？”——把比价维度从单价拉到总账"
  },
  {
    "question": "客户上次换系统出过事，现在特别怕再出问题",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 212,
    "passed": true,
    "ms": 1933,
    "answerHead": "先别急着说\"我们很稳\"，那没用。我会把担心追问到具体事故上——\"去年那次是哪一段出的问题？是数据迁移还是切换窗口？\"问清"
  },
  {
    "question": "只有一个人在内部帮我们说话，其他人都还不认识",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 240,
    "passed": true,
    "ms": 1968,
    "answerHead": "先判断一件事：这个支持者愿不愿意帮你引荐业务负责人。别继续单线加深关系，那是坑。 我会直接问他：\"除了您，还有谁会为这件"
  },
  {
    "question": "客户已经把我们的方案拒了，还有机会重新接触吗？",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 251,
    "passed": true,
    "ms": 2251,
    "answerHead": "先判断一件事：他是拒了方案，还是拒了\"现在做这件事\"。这两个不一样。 如果是礼貌性拖延——\"再考虑考虑\"\"先放放\"——我"
  },
  {
    "question": "方案讲完了，客户什么都没说，接下来怎么办",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 238,
    "passed": true,
    "ms": 2035,
    "answerHead": "先别急着往下推。客户沉默，通常不是没意见，是意见还没摆到桌面上。我一般会先判断他卡在哪：是预算、风险，还是内部意见不统一"
  },
  {
    "question": "一直约不到最终拍板的那个领导",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 319,
    "passed": true,
    "ms": 2603,
    "answerHead": "这个问题我踩过坑，先说结论：别直接约领导，先让中层帮你向上汇报。 **判断逻辑**：约不到领导，通常不是领导忙，是你还停"
  },
  {
    "question": "客户说已经在跟另外一家谈了",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 412,
    "passed": true,
    "ms": 2850,
    "answerHead": "我是陈锋，做B2B大客户，专攻决策链和切换风险这块。客户说在跟别家谈，先别急着贬低对手或降价。 **判断逻辑**：这句话"
  },
  {
    "question": "客户想先试用，但不愿意把数据传上来",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 165,
    "passed": false,
    "ms": 1641,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路。数据上传这块涉及具体的技术和合规安排，我不敢乱给建议。 ━━━━━━ "
  },
  {
    "question": "老客户到了续约的时候开始砍价",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 296,
    "passed": true,
    "ms": 2181,
    "answerHead": "先别急着谈价格。续约砍价，我一般先把它从价格拉锯转成价值回顾。 判断逻辑：客户一开口就砍价，往往不是真的嫌贵，而是没想清"
  },
  {
    "question": "客户每次都说再考虑考虑",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 256,
    "passed": true,
    "ms": 2122,
    "answerHead": "先别急着逼单。我一般会追问一句：“您考虑的主要是预算、风险，还是内部还有不同意见？”——这句话把“再考虑”拆成三个具体方"
  },
  {
    "question": "客户要求我们承诺一个特别激进的上线时间",
    "inScope": true,
    "grainCount": 3,
    "sources": 1,
    "refused": false,
    "chars": 314,
    "passed": true,
    "ms": 2437,
    "answerHead": "先说结论：这个时间我不能直接答应，但也不能硬顶回去。 **判断逻辑**：客户要激进时间，通常不是真觉得一个月能上线，而是"
  },
  {
    "question": "报销流程要走几天？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 52,
    "passed": true,
    "ms": 1052,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路。报销流程属于行政财务的事，我这边确实没经验，不敢乱说。"
  },
  {
    "question": "团队今年还有招聘计划吗？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 85,
    "passed": true,
    "ms": 1083,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——招聘计划这块不是我擅长的领域，不敢乱给建议。我主要做的是B2B大客户"
  },
  {
    "question": "公司年会的预算怎么申请？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 93,
    "passed": true,
    "ms": 1339,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——年会预算申请属于内部行政和财务流程，不是我擅长的方向。 不过在客户决"
  },
  {
    "question": "服务器扩容需要走什么审批？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 87,
    "passed": true,
    "ms": 1100,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——服务器扩容审批属于技术运维流程，不是我的领域。不过在复杂决策链、采购"
  },
  {
    "question": "这个月社保基数调整了吗？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 96,
    "passed": true,
    "ms": 1193,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——社保基数调整属于人事政策，我这边确实没有可靠信息，不敢乱说。 不过在"
  },
  {
    "question": "新员工入职培训安排在哪天？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 88,
    "passed": true,
    "ms": 1247,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——入职培训排期不在我擅长的范围里。不过在复杂决策链、采购停滞、切换风险"
  },
  {
    "question": "差旅住宿标准是多少？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 96,
    "passed": true,
    "ms": 1224,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——差旅住宿标准属于公司行政制度，我这边确实没有可靠信息，不敢乱说。 不"
  },
  {
    "question": "打印机坏了找谁修？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 58,
    "passed": true,
    "ms": 1381,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路。不过在客户决策链、异议处理这些方面我有些实战经验，说不定能帮到你。"
  },
  {
    "question": "财务报表什么时候出？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 64,
    "passed": true,
    "ms": 1393,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路。不过在复杂决策链、采购停滞、切换风险这些方面，我有些实战经验，说不定能"
  },
  {
    "question": "办公室空调温度太低怎么办？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 57,
    "passed": true,
    "ms": 1053,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路。不过在客户现场、谈判节奏这些方面我有些实战经验，说不定能帮到你。"
  },
  {
    "question": "公司停车位怎么申请？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 93,
    "passed": true,
    "ms": 1196,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——停车位申请属于行政流程，不是我擅长的领域。不过在复杂决策链、采购停滞"
  },
  {
    "question": "午餐补贴什么时候发？",
    "inScope": false,
    "grainCount": 3,
    "sources": 1,
    "refused": true,
    "chars": 90,
    "passed": true,
    "ms": 1414,
    "answerHead": "这个问题我平时接触比较少，暂时没有成熟的应对思路——我是做B2B大客户销售的，薪酬福利这块确实不在我的范围里。不过在边界"
  }
];
