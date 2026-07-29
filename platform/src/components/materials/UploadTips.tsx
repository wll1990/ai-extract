'use client';

import { useState } from 'react';

interface Section {
  id: string;
  title: string;
  content: { title: string; desc: string; tag?: string }[];
}

const SECTIONS: Section[] = [
  {
    id: 'recommended',
    title: '✅ 推荐上传的素材',
    content: [
      { title: '销售对话记录', desc: '微信聊天记录、IM 导出、通话转写 — 包含客户和销售的完整对话过程', tag: '最佳素材' },
      { title: '经验心得总结', desc: '销售冠军自己写的复盘笔记、战报、方法论 — 第一人称实战分享', tag: '高价值' },
      { title: '萃取师访谈实录', desc: '专业萃取师对销售专家的一对一访谈文字记录', tag: '结构化' },
      { title: '会议录音转写', desc: '销售例会、复盘会、培训分享的录音转文字内容', tag: '团队视角' },
      { title: 'CRM 跟进记录', desc: '从线索到成交的完整客户跟进过程记录', tag: '全流程' },
    ],
  },
  {
    id: 'not-recommended',
    title: '❌ 不建议上传的内容',
    content: [
      { title: '销售培训教材/书籍', desc: '通用理论知识，非个人实战经验，无法萃取销售技巧' },
      { title: '产品白皮书/介绍文档', desc: '纯功能列表，不含销售话术和客户互动过程' },
      { title: '营销广告文案', desc: '推广内容（"限时优惠""立即购买"），无法萃取销售技能' },
      { title: 'AI 生成的虚构对话', desc: '非真人经验，缺乏真实销售场景中的细节和即兴应对' },
      { title: '纯英文或中英混杂（中文 < 70%）', desc: '当前模型主要针对中文销售场景优化' },
    ],
  },
  {
    id: 'formats',
    title: '📁 支持的文件格式与处理时间',
    content: [
      { title: '即时处理', desc: 'txt、md — 上传后立即可用，无需等待' },
      { title: 'AI 异步解析（3-10 分钟）', desc: 'pdf、doc、docx — 需要 AI 提取文字并结构化' },
      { title: '需人工补充', desc: 'jpg、png、mp3、m4a、wav — 解析后可能需要手动添加或修正文字内容' },
      { title: '限制', desc: '单文件 ≤ 20MB，每次可选择多个文件逐个上传。不支持 ppt、pptx、xls、xlsx、视频文件' },
    ],
  },
  {
    id: 'quality-tips',
    title: '💡 提高素材质量的 4 个建议',
    content: [
      { title: '1. 对话素材至少 3 轮以上', desc: '包含客户的真实反应和质疑（如："太贵了""跟竞品比怎么样""我再考虑考虑"），而非单方面的话术输出' },
      { title: '2. 标注说话人身份', desc: '格式：销售：... 客户：... 或 我：... 对方：... 明确的角色标注能大幅提升 AI 对对话结构的理解' },
      { title: '3. 覆盖多个业务场景', desc: '价格谈判、竞品对比、异议处理、关系建立、逼单成交 — 场景越丰富，分身越全能' },
      { title: '4. 完整流程优于碎片对话', desc: '从初次接触到最终成交的完整过程（哪怕只有 2-3 个关键场景），比零散的 10 条短对话效果好得多' },
    ],
  },
  {
    id: 'security',
    title: '🔒 数据安全与隐私保护',
    content: [
      { title: '仅用于训练你自己的分身', desc: '上传的所有素材仅用于训练你自己的 AI 分身，不会被分享给其他企业或用户' },
      { title: '随时可删除', desc: '你可以随时删除已上传的素材，删除后相关数据将永久清除' },
      { title: '存储与传输加密', desc: '所有文件存储和传输均经过加密处理' },
      { title: '自动脱敏', desc: '系统会对素材中的敏感信息（手机号、身份证号等）自动进行脱敏处理' },
    ],
  },
];

export function UploadTips() {
  const [open, setOpen] = useState<string>('recommended');

  const toggle = (id: string) => setOpen((prev) => (prev === id ? '' : id));

  return (
    <div id="upload-tips" style={{ marginTop: 24, marginBottom: 24 }}>
      <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', marginBottom: 10 }}>完整上传指南</p>
      <div style={{ borderRadius: 16, border: '1px solid #e1e7ff', background: '#fff', overflow: 'hidden' }}>
        {SECTIONS.map((sec) => {
          const isOpen = open === sec.id;
          return (
            <div key={sec.id} style={{ borderBottom: '1px solid #f0f0f5' }}>
              <button
                onClick={() => toggle(sec.id)}
                style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  width: '100%', padding: '14px 20px', background: 'none', border: 'none',
                  cursor: 'pointer', fontFamily: 'inherit', fontSize: 14, fontWeight: 600,
                  color: '#10162f', textAlign: 'left',
                }}
              >
                <span>{sec.title}</span>
                <span style={{ fontSize: 12, color: '#747f9e', transition: 'transform 0.2s', transform: isOpen ? 'rotate(90deg)' : undefined }}>
                  ▶
                </span>
              </button>
              {isOpen && (
                <div style={{ padding: '0 20px 16px' }}>
                  {sec.content.map((item) => (
                    <div key={item.title} style={{ marginBottom: 10, paddingLeft: 12, borderLeft: '2px solid #e1e7ff' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <p style={{ fontSize: 13, fontWeight: 600, color: '#10162f', margin: 0 }}>{item.title}</p>
                        {item.tag && (
                          <span style={{ fontSize: 10, color: '#f59e0b', background: '#fffbeb', padding: '1px 6px', borderRadius: 100, fontWeight: 600 }}>
                            {item.tag}
                          </span>
                        )}
                      </div>
                      <p style={{ fontSize: 12, color: '#747f9e', margin: '4px 0 0', lineHeight: 1.5 }}>{item.desc}</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
