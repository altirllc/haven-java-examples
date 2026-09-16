import { useQuery } from '@tanstack/react-query';
import { getMe } from './client';

/** The signed-in user. One query key for every consumer — React Query dedupes
 *  the request, so the shell and its menu never fetch twice. */
export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: getMe, staleTime: 30_000 });
}
