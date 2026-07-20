'use client';

import React, { useEffect, useState } from 'react';

interface ToastProps {
  message: string | null;
  onDone: () => void;
  duration?: number;
}

export function Toast({ message, onDone, duration = 2500 }: ToastProps) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!message) { setVisible(false); return; }
    setVisible(true);
    const t = setTimeout(() => { setVisible(false); setTimeout(onDone, 300); }, duration);
    return () => clearTimeout(t);
  }, [message, duration, onDone]);

  if (!message && !visible) return null;

  return (
    <div className={`fixed top-6 left-1/2 -translate-x-1/2 z-50 transition-all duration-300 ${
      visible ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-4'
    }`}>
      <div className="rounded-xl bg-foreground px-5 py-3 text-sm text-white shadow-lg flex items-center gap-2">
        <span>{message || ''}</span>
      </div>
    </div>
  );
}
