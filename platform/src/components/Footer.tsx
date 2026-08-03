import Link from 'next/link';

export function Footer() {
  return (
    <footer style={{ textAlign: 'center', padding: '48px 40px' }}>
      <div style={{
        display: 'flex', gap: 28, justifyContent: 'center',
        marginBottom: 16, flexWrap: 'wrap',
      }}>
        <Link href="/discover" style={{ color: 'var(--fg-dim)', textDecoration: 'none', fontSize: 12 }}>发现专家</Link>
        <Link href="/login" style={{ color: 'var(--fg-dim)', textDecoration: 'none', fontSize: 12 }}>登录</Link>
        <Link href="/register" prefetch={false} style={{ color: 'var(--fg-dim)', textDecoration: 'none', fontSize: 12 }}>注册</Link>
      </div>
      <div style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
        &copy; {new Date().getFullYear()} MindForge &middot; Forge Expertise, Scale Minds
      </div>
    </footer>
  );
}
