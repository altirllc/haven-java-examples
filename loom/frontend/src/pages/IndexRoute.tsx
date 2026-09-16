import { Center, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router';
import { errorMessage, listCases } from '../api/client';
import { LoadErrorState } from '../components/LoadErrorState';
import { LandingPage } from './LandingPage';

/**
 * The app root is a decision, not a page: an empty board gets the landing page,
 * anything else goes straight to the board. It reads the same ['cases'] key the
 * board does, so the redirect costs no second request, and it replaces rather
 * than pushes so Back leaves the app instead of bouncing here.
 */
export function IndexRoute() {
  const { data: cases = [], isPending, error, refetch } = useQuery({
    queryKey: ['cases'],
    queryFn: listCases,
  });

  if (error) {
    return (
      <LoadErrorState
        title="Can't reach loom"
        detail={errorMessage(error, 'The API did not respond')}
        onRetry={() => refetch()}
      />
    );
  }

  // Deliberately not a skeleton: this resolves into a redirect for everyone
  // past their first run, and a ghost of a page they never see would flash.
  if (isPending) {
    return <Center h={300}><Loader size="sm" /></Center>;
  }

  return cases.length > 0 ? <Navigate to="/cases" replace /> : <LandingPage />;
}
