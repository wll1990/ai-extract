import Link from 'next/link';

export default function NotFound() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="text-center">
        <span className="text-6xl">🔍</span>
        <h1 className="mt-4 text-2xl font-bold text-foreground">404</h1>
        <p className="mt-2 text-muted-foreground">页面不存在或已被移除</p>
        <Link
          href="/skills"
          className="mt-6 inline-block rounded-lg bg-primary px-6 py-2 text-sm font-medium text-white hover:bg-primary-dark transition-colors"
        >
          返回分身广场
        </Link>
      </div>
    </div>
  );
}
