export default function ChatLoading() {
  return (
    <div style={{
      display: 'flex', height: '100vh', alignItems: 'center',
      justifyContent: 'center', background: 'var(--s1)',
    }}>
      <div style={{ textAlign: 'center' }}>
        <div style={{
          width: 48, height: 48, borderRadius: 14,
          background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: '#fff', fontSize: 24, margin: '0 auto 16px',
        }}>💎</div>
        <p style={{ fontSize: 13, color: 'var(--fg-low)' }}>加载专家信息...</p>
      </div>
    </div>
  );
}
