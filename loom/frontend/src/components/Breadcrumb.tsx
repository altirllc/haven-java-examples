import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link, useLocation } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { getCase } from '../api/client';
import classes from './Breadcrumb.module.css';

// Derived from the pathname — no per-page registration. HAVEN is the first
// crumb because it is the root of every Haven surface, not the app root —
// the app itself is the crumb beside it.
export function Breadcrumb() {
  const { pathname } = useLocation();
  const [head] = pathname.split('/').filter(Boolean);

  const caseId = head && head !== 'config' && head !== 'cases' ? head : undefined;
  const { data: item } = useQuery({
    queryKey: ['cases', caseId],
    queryFn: () => getCase(caseId!),
    enabled: !!caseId,
    staleTime: 30_000,
  });

  return (
    <Breadcrumbs separator="/" separatorMargin={8} className={classes.crumbs} classNames={{ separator: classes.separator }}>
      {/* Haven is the root of every Haven surface. A plain anchor, because the
          app's router basename would swallow a Link. */}
      <Anchor href="/console/dashboard" underline="never">
        <Text fw={700} ff="monospace" tt="uppercase" size="sm" variant="gradient" gradient={{ from: 'cyan.4', to: 'cyan.8' }}>
          HAVEN
        </Text>
      </Anchor>

      {head
        ? <Anchor component={Link} to="/" underline="hover" className={classes.link}>loom</Anchor>
        : <Text component="span" fw={600} className={classes.leaf}>loom</Text>}

      {head === 'config' && <Text component="span" fw={600} className={classes.leaf}>Config</Text>}
      {head === 'cases' && <Text component="span" fw={600} className={classes.leaf}>Cases</Text>}

      {!!caseId && <Anchor component={Link} to="/cases" underline="hover" className={classes.link}>Cases</Anchor>}
      {!!caseId && (
        <Text component="span" display="inline-block" maw={360} truncate fw={600} className={classes.leaf}>
          {item?.title ?? caseId}
        </Text>
      )}
    </Breadcrumbs>
  );
}
