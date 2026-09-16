import { Box, Paper, Stack } from '@mantine/core';
import classes from './AgentCockpit.module.css';
import { ChatView } from './ChatView';
import { AgentPanel } from './AgentPanel';
import { AgentHeader } from './AgentHeader';

/**
 * Agent mode: the agent's vital-signs header over chat (hero) + a live decision
 * feed rail. Fills the main area below the 56px app header. The agent's full
 * definition lives on the settings page (gear in the top nav).
 */
export function AgentCockpit({ active, onOpenCase }: { active: boolean; onOpenCase: (id: string) => void }) {
  return (
    <Stack gap="md" h="calc(100dvh - 56px - 2 * var(--mantine-spacing-md))">
      <AgentHeader />

      <Box className={classes.panes}>
        <Paper withBorder p="md" display="flex" mih={0} style={{ flexDirection: 'column' }}>
          <ChatView active={active} />
        </Paper>
        <Paper withBorder p="md" display="flex" mih={0} style={{ flexDirection: 'column' }} visibleFrom="sm">
          <AgentPanel onOpenCase={onOpenCase} />
        </Paper>
      </Box>
    </Stack>
  );
}
