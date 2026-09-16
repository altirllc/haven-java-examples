import { AppShell, Box, Burger, Group, Indicator, ActionIcon } from '@mantine/core';
import { useDisclosure, useHotkeys, useLocalStorage, useMediaQuery } from '@mantine/hooks';
import { useModals } from '@mantine/modals';
import { Outlet } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { Bot } from 'lucide-react';
import { openWatchCaseModal } from './WatchCaseModal';
import { AgentCockpit } from './AgentCockpit';
import { getAgentActivity, meetsRole } from '../api/client';
import { useMe } from '../api/useMe';
import { useAgentMode } from '../nav/agentMode';
import { useGo } from '../nav/useGo';
import { Breadcrumb } from './Breadcrumb';
import { Rail } from './Rail';
import { UserMenu } from './UserMenu';
import classes from './Layout.module.css';

export function Layout() {
  const modals = useModals();
  const go = useGo();
  const [mobileOpened, { toggle: toggleMobile, close: closeMobile }] = useDisclosure(false);
  // Rail width is a preference, not location state: it persists, and it is
  // shared across the tenant's apps (same origin) so the chrome doesn't change
  // shape app to app. Read synchronously — a deferred read paints collapsed
  // then jumps.
  const [railOpened, setRailOpened] = useLocalStorage({
    key: 'haven.rail',
    defaultValue: false,
    getInitialValueInEffect: false,
  });
  const toggleRail = () => setRailOpened((open) => !open);

  // Below the breakpoint the rail is a drawer, not a mini rail — opening it
  // from the burger must show labels, whatever the desktop width preference
  // says. Read synchronously so it never paints collapsed first.
  const isMobile = useMediaQuery('(max-width: 48em)', false, { getInitialValueInEffect: false });
  const railExpanded = isMobile || railOpened;

  // The whole app runs in one of two modes: the normal business app, or the
  // agent cockpit (chat + live activity). The agent runs autonomously in both.
  const { on: agentOn, toggle: toggleAgent } = useAgentMode();

  const { data: activity } = useQuery({ queryKey: ['agent', 'activity'], queryFn: getAgentActivity, refetchInterval: 5000 });
  const { data: me } = useMe();
  const canWrite = meetsRole(me?.roles ?? [], 'member');
  const alive = !!activity;
  // The backlog badge counts cases still being watched — the work the
  // agent has not yet reached.
  const watching = activity?.status.watching ?? 0;

  // The second arg clears tagsToIgnore: ⌘J is a mode toggle and must fire even
  // while typing in an input — above all the agent's own, or you can't leave.
  useHotkeys([['mod+J', toggleAgent]], []);

  const openWatch = () => openWatchCaseModal(modals);
  // Opening a case from the agent feed navigates — and navigation wins over
  // the mode, so the trip through go() exits the cockpit.
  const openCase = (id: string) => go(`/${id}`);

  return (
    <AppShell
      header={{ height: 56 }}
      // Collapsing narrows the rail to a mini icon rail rather than hiding it —
      // the page never flows under the reopen control.
      navbar={{ width: railExpanded ? 220 : 52, breakpoint: 'sm', collapsed: { mobile: !mobileOpened } }}
      transitionDuration={150}
      transitionTimingFunction="ease-in-out"
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          {/* miw={0} or the nowrap trail sets this group's floor and pushes the
              actions off-screen on a phone. */}
          <Group gap={8} wrap="nowrap" miw={0}>
            <Burger opened={mobileOpened} onClick={toggleMobile} hiddenFrom="sm" size="sm" aria-label="Toggle navigation" />
            <Breadcrumb />
          </Group>

          {/* Chrome only: the mode toggle and you. Locations live in the rail,
              actions on the page. */}
          <Group gap="xs" wrap="nowrap">
            {/* Backlog count when items wait, a breathing dot when the daemon
                is merely alive, nothing at all inside the cockpit. */}
            <Indicator
              label={watching > 0 ? watching : undefined}
              size={watching > 0 ? 18 : 10}
              color={watching > 0 ? 'yellow' : 'green'}
              processing={watching === 0}
              disabled={agentOn || (watching === 0 && !alive)}
              offset={4}
              withBorder
            >
              <ActionIcon
                variant="default"
                size="lg"
                className={classes.agentToggle}
                data-on={agentOn || undefined}
                onClick={toggleAgent}
                aria-label="Toggle agent"
                title="Agent mode (⌘J)"
              >
                <Bot size={18} />
              </ActionIcon>
            </Indicator>

            <UserMenu />
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar className={classes.navbar}>
        <Rail expanded={railExpanded} toggle={toggleRail} onNavigate={closeMobile} />
      </AppShell.Navbar>

      <AppShell.Main>
        {/* Both stay mounted; we toggle visibility so page and chat state survive a mode switch. */}
        <Box display={agentOn ? 'none' : undefined}>
          <Outlet context={{ openWatch, canWrite }} />
        </Box>
        <Box display={agentOn ? undefined : 'none'}>
          <AgentCockpit active={agentOn} onOpenCase={openCase} />
        </Box>
      </AppShell.Main>
    </AppShell>
  );
}
