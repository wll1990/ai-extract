'use client';

import React from 'react';

/**
 * 上传前必看 — 素材标准说明
 * 右侧面板常驻展示，不折叠
 */
export function MaterialUploadGuide() {
  return (
    <div className="space-y-4 text-sm text-foreground">
      {/* 接收什么 */}
      <section>
        <h4 className="font-semibold text-foreground mb-2">✅ 我们接收什么</h4>
        <div className="grid gap-2">
          {[
            { label: '销售对话记录', desc: '微信聊天、IM 导出、通话转写——客户和销售的完整对话' },
            { label: '经验心得', desc: '销冠自己写的总结、复盘笔记、战报——第一人称实战分享' },
            { label: '访谈实录', desc: '萃取师访谈销冠的文字记录，一问一答' },
            { label: '会议录音转写', desc: '销售例会、复盘会、培训分享的录音转文字' },
            { label: 'CRM 跟客记录', desc: '从线索到成交的完整跟进过程' },
          ].map(item => (
            <div key={item.label} className="flex items-start gap-2 rounded-lg bg-surface-2 px-3 py-2">
              <span className="text-green-500 flex-shrink-0 mt-0.5">✓</span>
              <div>
                <span className="font-medium">{item.label}</span>
                <span className="text-muted-foreground-2"> — {item.desc}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 不接收什么 */}
      <section>
        <h4 className="font-semibold text-foreground mb-2">❌ 不接收</h4>
        <div className="grid gap-1.5">
          {[
            { label: '销售培训教材/书籍', reason: '通用理论，非个人实战经验' },
            { label: '产品白皮书/介绍文档', reason: '产品功能列表，不含销售技巧' },
            { label: '营销广告文案', reason: '"限时优惠""立即购买"——这是推广内容' },
            { label: '纯英文素材', reason: '当前仅支持中文销售对话' },
            { label: 'AI 生成的虚构对话', reason: '非真人经验，无实际萃取价值' },
          ].map(item => (
            <div key={item.label} className="flex items-start gap-2 rounded-lg bg-surface-2 px-3 py-2">
              <span className="text-red-400 flex-shrink-0 mt-0.5">✗</span>
              <div>
                <span className="font-medium">{item.label}</span>
                <span className="text-muted-foreground-2"> — {item.reason}</span>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* 文件格式 */}
      <section>
        <h4 className="font-semibold text-foreground mb-2">📁 支持的文件格式</h4>

        <p className="text-xs text-muted-foreground mb-1.5">
          <span className="text-green-500 font-medium">● 即时处理</span> — 上传后直接预检，无需等待
        </p>
        <div className="flex flex-wrap gap-1.5 mb-2">
          {['.txt', '.md', '.csv', '.html', '.json', '.xml'].map(f =>
            <span key={f} className="rounded-full bg-green-50 border border-green-200 px-2.5 py-0.5 text-xs text-green-700">{f}</span>
          )}
        </div>

        <p className="text-xs text-muted-foreground mb-1.5">
          <span className="text-blue-500 font-medium">● AI 解析</span> — 异步处理，完成后可萃取
        </p>
        <div className="flex flex-wrap gap-1.5 mb-2">
          {['.pdf', '.docx', '.doc', '.xlsx', '.xls'].map(f =>
            <span key={f} className="rounded-full bg-blue-50 border border-blue-200 px-2.5 py-0.5 text-xs text-blue-700">{f}</span>
          )}
        </div>

        <p className="text-xs text-muted-foreground mb-1.5">
          <span className="text-orange-500 font-medium">● 需人工补充</span> — 图片/音频解析后需手动添加文字描述
        </p>
        <div className="flex flex-wrap gap-1.5">
          {['.png', '.jpg', '.jpeg', '.mp3', '.wav', '.m4a'].map(f =>
            <span key={f} className="rounded-full bg-orange-50 border border-orange-200 px-2.5 py-0.5 text-xs text-orange-700">{f}</span>
          )}
        </div>

        <p className="text-xs text-muted-foreground-2 mt-2">单个文件 ≤ 100MB，每次最多 5 个文件</p>
      </section>

      {/* 硬性要求 */}
      <section>
        <h4 className="font-semibold text-foreground mb-2">⚙️ 硬性要求（不满足会被自动拒绝）</h4>
        <div className="space-y-1 text-xs text-muted-foreground">
          <p>• 中文占比 ≥ 70%（中英混杂可接受但中文必须为主）</p>
          <p>• 包含业务相关关键词</p>
          <p>• 真人真实经验，需使用第一人称（"我做过""我遇到过"）</p>
          <p>• 有效内容 ≥ 50 字符</p>
          <p>• 不与已上传素材高度重复</p>
        </div>
      </section>

      {/* 质量建议 */}
      <section>
        <h4 className="font-semibold text-foreground mb-2">💡 提高萃取质量的建议</h4>
        <div className="space-y-1 text-xs text-muted-foreground">
          <p>• 对话素材至少 3 轮以上，包含客户真实质疑（"太贵了""跟竞品比呢"）</p>
          <p>• 覆盖多个话题：价格谈判、竞品对比、异议处理、关系建立等</p>
          <p>• 标注说话人："销售：... 客户：..." 或 "我：... 对方：..."</p>
          <p>• 从初次接触到成交的完整过程，比碎片对话好得多</p>
        </div>
      </section>
    </div>
  );
}
