import { ActionIcon, AppShell, Group, NavLink, ScrollArea, Stack } from '@mantine/core';
import { Link, useLocation } from 'react-router';
import { LayoutGrid, PanelLeftClose, PanelLeftOpen, SlidersVertical } from 'lucide-react';
import classes from './Rail.module.css';

// The app's locations, and nothing else — actions live on the page, modes and
// identity live in the header.
const SECTIONS = [
  // The root is a redirect, not a location, so nothing is active there; item
  // detail pages live at /:id and belong to Items.
  { label: 'Cases', to: '/cases', icon: LayoutGrid, isActive: (p: string) => p !== '/' && !p.startsWith('/config') },
  { label: 'Config', to: '/config', icon: SlidersVertical, isActive: (p: string) => p.startsWith('/config') },
];

export function Rail({ expanded, toggle, onNavigate }: {
  expanded: boolean;
  toggle: () => void;
  onNavigate?: () => void;
}) {
  const { pathname } = useLocation();

  // Collapsed = a mini icon rail: same sections, same order, keeping its own
  // column so page content never flows under it.
  if (!expanded) {
    return (
      <>
        <AppShell.Section grow pt="md">
          <Stack gap={4} align="center">
            {SECTIONS.map(({ label, to, icon: Icon, isActive }) => (
              <ActionIcon
                key={to}
                component={Link}
                to={to}
                variant={isActive(pathname) ? 'light' : 'subtle'}
                color={isActive(pathname) ? 'cyan' : 'gray'}
                size="lg"
                aria-label={label}
                onClick={onNavigate}
              >
                <Icon size={18} />
              </ActionIcon>
            ))}
          </Stack>
        </AppShell.Section>
        <AppShell.Section py="xs" className={classes.footer}>
          <Group justify="center">
            <ActionIcon variant="subtle" color="gray" onClick={toggle} aria-label="Expand rail">
              <PanelLeftOpen size={16} />
            </ActionIcon>
          </Group>
        </AppShell.Section>
      </>
    );
  }

  return (
    <>
      <AppShell.Section grow component={ScrollArea} px="md" pt="md">
        {SECTIONS.map(({ label, to, icon: Icon, isActive }) => (
          <NavLink
            key={to}
            component={Link}
            to={to}
            label={label}
            leftSection={<Icon size={18} />}
            active={isActive(pathname)}
            onClick={onNavigate}
          />
        ))}
      </AppShell.Section>

      <AppShell.Section px="md" py="xs" className={classes.footer}>
        <Group justify="flex-end">
          <ActionIcon variant="subtle" color="gray" onClick={toggle} visibleFrom="sm" aria-label="Collapse rail">
            <PanelLeftClose size={16} />
          </ActionIcon>
        </Group>
      </AppShell.Section>
    </>
  );
}
