const LOG_LEVELS = { debug: 0, info: 1, warn: 2, error: 3 } as const;
type Level = keyof typeof LOG_LEVELS;

const currentLevel: Level = process.env.NODE_ENV === 'production' ? 'warn' : 'debug';

function log(level: Level, ...args: any[]) {
  if (LOG_LEVELS[level] >= LOG_LEVELS[currentLevel]) {
    const ts = new Date().toISOString();
    const prefix = `[${ts}] [${level.toUpperCase()}]`;
    switch (level) {
      case 'error': console.error(prefix, ...args); break;
      case 'warn': console.warn(prefix, ...args); break;
      default: console.log(prefix, ...args); break;
    }
  }
}

export const logger = {
  debug: (...args: any[]) => log('debug', ...args),
  info: (...args: any[]) => log('info', ...args),
  warn: (...args: any[]) => log('warn', ...args),
  error: (...args: any[]) => log('error', ...args),
};
