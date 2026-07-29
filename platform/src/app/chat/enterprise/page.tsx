'use client';

import { ChatView } from '@/components/chat/ChatView';

const ENTERPRISE_SKILL = {
  id: '__enterprise__',
  displayName: '企业智慧总调度',
  ownerName: '企业智慧',
  ownerTitle: '向全公司经验库提问',
  tags: [],
  sceneTags: [],
  grainCount: 0,
  domain: 'sales',
  status: 'published',
  stats: undefined,
};

export default function EnterpriseChatPage() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '8px 20px', borderBottom: '1px solid var(--s5)',
        background: 'var(--surface)', flexShrink: 0,
      }}>
        <a href="/discover" style={{
          background: 'none', border: 'none', cursor: 'pointer',
          fontSize: 13, color: 'var(--fg-mid)', textDecoration: 'none',
        }}>
          ← 返回发现页
        </a>
        <span style={{ fontSize: 12, color: 'var(--fg-dim)' }}>企业总调度</span>
        <div style={{ width: 48 }} />
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        <ChatView skill={ENTERPRISE_SKILL as any} enterprise />
      </div>
    </div>
  );
}
