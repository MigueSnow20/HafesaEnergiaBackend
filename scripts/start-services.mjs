import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const publicPort = Number(process.env.PORT ?? 3000);
const springPort = Number(process.env.SPRING_PORT ?? 8080);
const sourceTimeoutMs = Number(process.env.MARKET_TIMEOUT_MS ?? 8000);
if (![publicPort, springPort].every(port => Number.isInteger(port) && port > 0 && port <= 65535)
    || publicPort === springPort) {
  throw new Error('PORT and SPRING_PORT must be valid, different ports');
}
if (!Number.isFinite(sourceTimeoutMs) || sourceTimeoutMs <= 0) {
  throw new Error('MARKET_TIMEOUT_MS must be positive');
}
const springOrigin = `http://127.0.0.1:${springPort}`;
const legacyOrigin = `http://127.0.0.1:${publicPort}`;
const jar = process.env.SPRING_JAR ?? resolve(root,
  'petroxpert-ms-application/target/petroxpert-ms-application-1.0.0.jar');
const children = [];
let stopping = false;
let remaining = 2;
let forceStop;

function stop(code) {
  if (stopping) return;
  stopping = true;
  process.exitCode = code;
  for (const child of children) child.kill('SIGTERM');
  forceStop = setTimeout(() => {
    for (const child of children) child.kill('SIGKILL');
    process.exit(code);
  }, 15000);
  forceStop.unref();
}
function start(name, command, args, env) {
  const child = spawn(command, args, { cwd: root, env, stdio: 'inherit' });
  children.push(child);
  child.on('error', () => {
    console.error(`Could not start ${name}`);
    stop(1);
  });
  child.on('close', code => {
    remaining -= 1;
    if (!stopping) {
      console.error(`${name} exited; stopping the application`);
      stop(code || 1);
    }
    if (remaining === 0) clearTimeout(forceStop);
  });
}
process.on('SIGTERM', () => stop(0));
process.on('SIGINT', () => stop(0));
start('legacy', process.execPath, ['server.js'], {
  ...process.env, PORT: String(publicPort), SPRING_INTERNAL_URL: springOrigin,
  MARKET_TIMEOUT_MS: String(sourceTimeoutMs),
});
start('Spring', process.env.JAVA_BIN ?? 'java', [
  '-XX:MaxRAMPercentage=25', '-jar', jar,
  '--spring.profiles.active=v1', '--server.address=127.0.0.1', `--server.port=${springPort}`,
  '--petroxpert.market.provider=legacy', '--petroxpert.market.stale-after=90s',
  `--petroxpert.market.source-timeout=${sourceTimeoutMs * 6 + 5000}ms`, `--legacy.base-url=${legacyOrigin}`,
], process.env);
