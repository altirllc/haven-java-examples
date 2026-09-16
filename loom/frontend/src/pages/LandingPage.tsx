import { Box, Button, Group, Stack, Text } from '@mantine/core';
import { useOutletContext } from 'react-router';
import { Eye, Plus } from 'lucide-react';
import classes from './LandingPage.module.css';

const PURPOSE =
  "A supervisor for Anvil. Every item Anvil's triager decides gets a case here, an autonomous agent "
  + 'reviews the decision, and a case nobody settles before its deadline lapses.';

/** The app's first run, and only its first run — the index route sends anyone
 *  with cases straight to the board, so this never competes with the list. */
export function LandingPage() {
  const { openWatch, canWrite } = useOutletContext<{ openWatch: () => void; canWrite: boolean }>();

  return (
    <Box className={classes.root}>
      <Stack className={classes.copy} gap={0}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600} className={classes.eyebrow}>Haven app</Text>
        {/* Text, not Title: only Text carries the gradient variant. component="h1"
            keeps the page's one real heading semantic. */}
        <Text
          component="h1"
          ff="monospace"
          tt="uppercase"
          fw={700}
          lh={1.05}
          className={classes.name}
          variant="gradient"
          gradient={{ from: 'cyan.4', to: 'cyan.8' }}
        >
          loom
        </Text>
        <Text size="md" c="dimmed" mt="lg" lh={1.7}>{PURPOSE}</Text>

        {canWrite && (
          <Group gap="xs" mt="xl">
            <Button leftSection={<Plus size={16} />} onClick={openWatch}>
              Watch your first Anvil item
            </Button>
          </Group>
        )}
      </Stack>

      <Box pos="relative" h="100%" mih={340} visibleFrom="sm" aria-hidden>
        <Box className={classes.orb}>
          <Box component="span" className={classes.ring} />
          <Box component="span" className={`${classes.ring} ${classes.ringInner}`} />
          <Box component="span" className={`${classes.ring} ${classes.ringAccent}`} />
          <Eye size={86} strokeWidth={1.25} />
        </Box>
      </Box>
    </Box>
  );
}
