import { createTheme, Modal, NavLink, type MantineColorsTuple } from '@mantine/core';

const cyan: MantineColorsTuple = [
  '#e0feff',
  '#b3f5fa',
  '#80ecf4',
  '#4de3ef',
  '#22d3ee',
  '#06b6d4',
  '#0891b2',
  '#0e7490',
  '#155e75',
  '#164e63',
];

const magenta: MantineColorsTuple = [
  '#fde8ff',
  '#f5c4ff',
  '#f09eff',
  '#f038ff',
  '#d926e0',
  '#b81cc2',
  '#9614a3',
  '#750e84',
  '#560965',
  '#3a0546',
];

export const theme = createTheme({
  primaryColor: 'cyan',
  colors: {
    cyan,
    magenta,
  },
  fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif',
  fontFamilyMonospace: 'Fira Code, ui-monospace, SFMono-Regular, Menlo, Monaco, monospace',
  defaultRadius: 'md',
  cursorType: 'pointer',
  components: {
    // Modals mount and unmount synchronously. The default exit transition
    // paints a collapsing title/X shell mid-close — subtle on short modals,
    // a visible flash on tall ones (house standard, same as console/admin).
    Modal: Modal.extend({
      defaultProps: {
        centered: true,
        padding: 'xl',
        overlayProps: { backgroundOpacity: 0.55, blur: 8 },
        transitionProps: { duration: 0, exitDuration: 0 },
      },
    }),
    // Rounded, inset highlight for hover/active (vs Mantine's default
    // full-bleed block); nowrap labels so text never rewraps while the rail
    // width animates.
    NavLink: NavLink.extend({
      styles: {
        root: { borderRadius: 'var(--mantine-radius-md)' },
        label: { whiteSpace: 'nowrap' },
      },
    }),
  },
});
