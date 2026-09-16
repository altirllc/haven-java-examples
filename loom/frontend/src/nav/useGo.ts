import { useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router';

// Internal navigation: an explicit nav always wins over agentic mode (?agent=1)
// — going somewhere exits the backstage, including a nav to the current page.
export function useGo() {
  const navigate = useNavigate();
  const location = useLocation();
  return useCallback(
    (to: string) => {
      const agent = new URLSearchParams(location.search).get('agent') === '1';
      if (location.pathname === to && !agent) return;
      navigate(to);
    },
    [navigate, location.pathname, location.search],
  );
}
