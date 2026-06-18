import { Pool, PoolConfig } from 'pg';
import { env } from '@/config/env';
import { logger } from '@/shared/utils/logger';

/**
 * PostgreSQL connection pool.
 *
 * A pool maintains multiple persistent database connections.
 * Rather than opening a new connection per request (expensive ~50ms),
 * requests borrow an existing connection from the pool, use it, and return it.
 *
 * Pool sizing rule of thumb: (2 × CPU cores) + effective_spindle_count
 * For MVP: 10 is safe for hundreds of concurrent users.
 */
const poolConfig: PoolConfig = {
  host: env.DB_HOST,
  port: env.DB_PORT,
  database: env.DB_NAME,
  user: env.DB_USER,
  password: env.DB_PASSWORD,
  ssl: env.DB_SSL ? { rejectUnauthorized: true } : false,

  // Pool settings
  max: 10,                // Maximum connections in pool
  min: 2,                 // Minimum connections kept alive
  idleTimeoutMillis: 30_000,     // Close idle connections after 30s
  connectionTimeoutMillis: 5_000, // Fail fast if DB is unreachable
  allowExitOnIdle: false, // Keep pool alive even when idle
};

export const pool = new Pool(poolConfig);

/**
 * Test the connection on startup.
 * If the database is unreachable, the server should know immediately — not on the first request.
 */
export async function connectDatabase(): Promise<void> {
  try {
    const client = await pool.connect();
    const result = await client.query('SELECT NOW() as current_time, version()');
    const row = result.rows[0] as { current_time: Date; version: string };

    logger.info('Database connected', {
      host: env.DB_HOST,
      database: env.DB_NAME,
      serverTime: row.current_time.toISOString(),
      postgresVersion: row.version.split(' ')[1],
    });

    client.release(); // ALWAYS release the client back to the pool
  } catch (error) {
    logger.error('Database connection failed', {
      host: env.DB_HOST,
      database: env.DB_NAME,
      error: error instanceof Error ? error.message : String(error),
    });
    // Exit the process — a payment app with no DB is useless and dangerous
    process.exit(1);
  }
}

/**
 * Gracefully close all pool connections.
 * Called during server shutdown to prevent connection leaks.
 */
export async function disconnectDatabase(): Promise<void> {
  await pool.end();
  logger.info('Database pool closed');
}

// Log pool errors (e.g. DB goes down while server is running)
pool.on('error', (err) => {
  logger.error('Unexpected database pool error', {
    message: err.message,
    stack: err.stack,
  });
});