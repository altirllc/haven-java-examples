import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@mantine/core/styles.css';
import '@mantine/dropzone/styles.css';
import '@mantine/code-highlight/styles.css';
import '@mantine/notifications/styles.css';
import { MantineProvider } from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { Notifications } from '@mantine/notifications';
import { CodeHighlightAdapterProvider, createShikiAdapter } from '@mantine/code-highlight';
import { createHighlighter } from 'shiki';
import { BrowserRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { theme } from './theme';
import { App } from './App';

const shikiAdapter = createShikiAdapter(() =>
  createHighlighter({
    langs: ['bash', 'json', 'yaml', 'xml', 'sql', 'typescript', 'css', 'python'],
    themes: [],
  })
);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000 },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <MantineProvider theme={theme} defaultColorScheme="auto">
        <Notifications />
        <CodeHighlightAdapterProvider adapter={shikiAdapter}>
          <BrowserRouter basename={import.meta.env.BASE_URL.replace(/\/$/, '')}>
            <ModalsProvider>
              <App />
            </ModalsProvider>
          </BrowserRouter>
        </CodeHighlightAdapterProvider>
      </MantineProvider>
    </QueryClientProvider>
  </StrictMode>
);
