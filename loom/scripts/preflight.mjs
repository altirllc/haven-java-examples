#!/usr/bin/env node
// Checks every port local dev needs and reports ALL conflicts at once,
// before docker compose or any app process starts. Stdlib only.
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import net from 'node:net';

const envPort = (key, fallback) => {
  for (const file of ['../api/.env', '../.env']) {
    try {
      const match = readFileSync(new URL(file, import.meta.url), 'utf-8')
        .match(new RegExp(`^${key}=(\\d+)`, 'm'));
      if (match) return Number(match[1]);
    } catch {
      // fall through
    }
  }
  return fallback;
};

const checks = [
  { port: 5432, name: 'postgres', compose: true },
  { port: 27017, name: 'mongodb', compose: true },
  { port: 7233, name: 'temporal', compose: true },
  { port: 8080, name: 'temporal-ui', compose: true },
  { port: 8333, name: 'seaweedfs', compose: true },
  { port: envPort('PORT', 3000), name: 'api', compose: false },
  { port: envPort('MCP_PORT', 3001), name: 'mcp', compose: false },
  { port: 5173, name: 'ui', compose: false, warnOnly: true },
];

// Ports held by this app's own compose stack are not conflicts.
const composeRunning = (() => {
  try {
    return execSync('docker compose ps -q', { stdio: ['ignore', 'pipe', 'ignore'] })
      .toString().trim().length > 0;
  } catch {
    return false;
  }
})();

const inUse = (port) =>
  new Promise((resolve) => {
    const socket = net.connect({ port, host: '127.0.0.1' });
    socket.setTimeout(500);
    socket.once('connect', () => { socket.destroy(); resolve(true); });
    socket.once('timeout', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(false));
  });

const owner = (port) => {
  try {
    const lines = execSync(`lsof -nP -iTCP:${port} -sTCP:LISTEN`, {
      stdio: ['ignore', 'pipe', 'ignore'],
    }).toString().trim().split('\n');
    const [command, pid] = lines[1].split(/\s+/);
    return `${command} (pid ${pid})`;
  } catch {
    return 'unknown process';
  }
};

const conflicts = [];
const warnings = [];
for (const check of checks) {
  if (check.compose && composeRunning) continue;
  if (await inUse(check.port)) {
    (check.warnOnly ? warnings : conflicts).push(check);
  }
}

for (const { port, name } of warnings) {
  console.warn(`⚠ Port ${port} (${name}) is in use by ${owner(port)} — vite will pick the next free port.`);
}

if (conflicts.length > 0) {
  console.error('Cannot start: required ports are in use.\n');
  for (const { port, name } of conflicts) {
    console.error(`  ✗ ${port} (${name}) — in use by ${owner(port)}`);
  }
  console.error('\nFree the ports above, then re-run npm run dev.');
  process.exit(1);
}
