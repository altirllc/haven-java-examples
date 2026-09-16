import { useState } from 'react';
import {
  Avatar, Badge, Box, Group, Menu, SegmentedControl, Text, UnstyledButton,
  useMantineColorScheme,
} from '@mantine/core';
import { LogOut, Monitor, Moon, Sun, User } from 'lucide-react';
import { useMe } from '../api/useMe';
import classes from './UserMenu.module.css';

const SCHEMES = [
  { value: 'light', label: 'Light', Icon: Sun },
  { value: 'dark', label: 'Dark', Icon: Moon },
  { value: 'auto', label: 'Auto', Icon: Monitor },
];

/**
 * The user's own block and nothing else: who you are, where you are, how you
 * like to see it, and the way out. App destinations and modes live in the
 * navbar, never here.
 */
export function UserMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const { data: me } = useMe();

  const email = me?.email ?? '—';
  const displayName = email.includes('@') ? email.split('@')[0] : email;
  const roles = me?.roles ?? [];
  const [opened, setOpened] = useState(false);

  return (
    <Menu position="bottom-end" width={300} withArrow opened={opened} onChange={setOpened}>
      <Menu.Target>
        <UnstyledButton aria-label="User menu" className={classes.target}>
          <Avatar radius="xl" size={32} classNames={{ placeholder: classes.placeholder }}>
            <User size={16} />
          </Avatar>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Group gap="sm" px="sm" py="xs" wrap="nowrap" align="flex-start">
          <Avatar radius="xl" classNames={{ placeholder: classes.placeholder }}>
            <User size={18} />
          </Avatar>
          <Box miw={0} flex={1}>
            <Text size="sm" fw={700} truncate>{displayName}</Text>
            <Text size="xs" c="dimmed" truncate title={email}>{email}</Text>
            {me?.tenant && <Text size="xs" c="dimmed" ff="monospace" truncate>{me.tenant}</Text>}
          </Box>
          <Group gap={4} flex="0 0 auto">
            {roles.length
              ? roles.map((r) => <Badge key={r} size="xs" variant="light">{r}</Badge>)
              : <Badge size="xs" variant="light" color="gray">viewer</Badge>}
          </Group>
        </Group>
        <Box px="sm" pb={8}>
          <SegmentedControl
            fullWidth
            size="xs"
            value={colorScheme}
            onChange={(value) => { setColorScheme(value as 'light' | 'dark' | 'auto'); setOpened(false); }}
            data={SCHEMES.map(({ value, label, Icon }) => ({
              value,
              label: (
                <Group gap={6} justify="center" wrap="nowrap">
                  <Icon size={14} />
                  {label}
                </Group>
              ),
            }))}
          />
        </Box>
        <Menu.Divider />
        <Menu.Item
          leftSection={<LogOut size={16} />}
          onClick={() => { window.location.href = '/_edge/logout'; }}
        >
          Sign out
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}
