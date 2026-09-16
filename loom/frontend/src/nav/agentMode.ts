import { useCallback } from 'react';
import { useSearchParams } from 'react-router';

// Agentic mode lives in the URL: ?agent=1 on any path. It survives refresh,
// shares, and is undone by Back like any other navigation.
export function useAgentMode() {
  const [searchParams, setSearchParams] = useSearchParams();
  const on = searchParams.get('agent') === '1';
  const toggle = useCallback(() => {
    setSearchParams((prev) => {
      const next = new URLSearchParams(prev);
      if (next.get('agent') === '1') next.delete('agent');
      else next.set('agent', '1');
      return next;
    });
  }, [setSearchParams]);
  return { on, toggle };
}
