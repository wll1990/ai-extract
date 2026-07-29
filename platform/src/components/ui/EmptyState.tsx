'use client';

interface EmptyStateProps {
  icon?: string;
  title: string;
  description?: string;
  action?: { label: string; onClick: () => void };
}

export default function EmptyState({ icon = '📭', title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
      <span className="text-4xl mb-4">{icon}</span>
      <h3 className="text-base font-semibold text-[#10162f] mb-1.5">{title}</h3>
      {description && <p className="text-sm text-[#747f9e] mb-5 max-w-xs">{description}</p>}
      {action && (
        <button
          onClick={action.onClick}
          className="px-5 py-2.5 rounded-full bg-[#2147ff] text-white text-sm font-medium hover:translate-y-[-1px] transition-transform"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
