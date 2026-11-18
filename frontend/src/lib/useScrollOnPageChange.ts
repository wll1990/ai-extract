'use client';

import { useEffect, useRef } from 'react';

/**
 * 列表翻页后回到顶部。
 *
 * <p>分页按钮在列表底部，长列表翻页后如果视口仍停在底部，用户只会看到
 * 同样的页脚，容易误以为「分页是假的」。首次挂载不滚动，只在页码变化时触发。</p>
 */
export function useScrollOnPageChange(page: number) {
  const mounted = useRef(false);
  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true;
      return;
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [page]);
}
