import Link from 'next/link';

export function Navbar() {
  return (
    <nav
      style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '14px 40px', maxWidth: 1280, margin: '0 auto',
        position: 'relative', zIndex: 100,
      }}
    >
      <Link href="/" style={{
        fontWeight: 800, fontSize: 17, letterSpacing: '-0.03em',
        display: 'flex', alignItems: 'center', gap: 8,
        textDecoration: 'none', color: 'var(--fg-high)',
      }}>
        <span style={{
          width: 32, height: 32, borderRadius: 9,
          background: 'linear-gradient(135deg, var(--s12), var(--tangerine))',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: 16,
        }}>💎</span>
        MindForge
      </Link>

      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Link href="/discover" className="btn btn-ghost">发现专家</Link>
        <Link href="/login" className="btn btn-primary">登录</Link>
      </div>
    </nav>
  );
}
