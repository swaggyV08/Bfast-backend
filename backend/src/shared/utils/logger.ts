type LogLevel = 'info' | 'warn' | 'error' | 'debug';

interface LogEntry {
  timestamp: string;
  level: LogLevel;
  message: string;
  correlationId?: string;
  [key: string]: unknown;
}

/**
 * Structured JSON logger for production use.
 * Outputs JSON lines that CloudWatch, Datadog, etc. can parse and index.
 */
class Logger {
  private formatEntry(level: LogLevel, message: string, meta?: Record<string, unknown>): LogEntry {
    return {
      timestamp: new Date().toISOString(),
      level,
      message,
      ...meta,
    };
  }

  info(message: string, meta?: Record<string, unknown>): void {
    console.info(JSON.stringify(this.formatEntry('info', message, meta)));
  }

  warn(message: string, meta?: Record<string, unknown>): void {
    console.warn(JSON.stringify(this.formatEntry('warn', message, meta)));
  }

  error(message: string, meta?: Record<string, unknown>): void {
    console.error(JSON.stringify(this.formatEntry('error', message, meta)));
  }

  debug(message: string, meta?: Record<string, unknown>): void {
    if (process.env['NODE_ENV'] !== 'production') {
      console.info(JSON.stringify(this.formatEntry('debug', message, meta)));
    }
  }
}

export const logger = new Logger();