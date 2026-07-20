export default function SkillsGalleryLoading() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-surface">
      <div className="flex flex-col items-center gap-4">
        <div className="h-10 w-10 animate-spin rounded-full border-4 border-primary-light border-t-primary" />
        <p className="text-sm text-muted-foreground">加载分身广场...</p>
      </div>
    </div>
  );
}
