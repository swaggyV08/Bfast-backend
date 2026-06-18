import { query } from '@/database/query';
import { logger } from '@/shared/utils/logger';

/**
 * Background cleanup worker.
 *
 * Runs periodic tasks to maintain database hygiene:
 * 1. Expire stale session codes past their 30-second TTL
 * 2. Delete bump requests older than 10 seconds
 * 3. Clean up expired (and revoked) refresh tokens older than 30 days
 */

const CLEANUP_INTERVAL_MS = 30_000; // Run every 30 seconds

let cleanupTimer: ReturnType<typeof setInterval> | null = null;

async function runCleanup(): Promise<void> {
  try {
    // 1. Expire stale session codes
    const sessionResult = await query(
      `UPDATE session_codes
       SET status = 'expired'
       WHERE expires_at < NOW() AND status = 'active'`,
    );

    // 2. Delete old bump requests
    const bumpResult = await query(
      `DELETE FROM bump_requests WHERE expires_at < NOW()`,
    );

    // 3. Clean up old revoked refresh tokens (>30 days)
    const tokenResult = await query(
      `DELETE FROM refresh_tokens
       WHERE is_revoked = TRUE AND issued_at < NOW() - INTERVAL '30 days'`,
    );

    const expired = sessionResult.rowCount ?? 0;
    const bumpsDeleted = bumpResult.rowCount ?? 0;
    const tokensDeleted = tokenResult.rowCount ?? 0;

    // Only log if work was actually done (avoid log spam)
    if (expired > 0 || bumpsDeleted > 0 || tokensDeleted > 0) {
      logger.debug('Cleanup completed', {
        expiredSessionCodes: expired,
        deletedBumpRequests: bumpsDeleted,
        deletedRefreshTokens: tokensDeleted,
      });
    }
  } catch (error) {
    logger.error('Cleanup worker error', {
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

/**
 * Starts the background cleanup worker.
 * Called from server.ts after database is connected.
 */
export function startCleanupWorker(): void {
  if (cleanupTimer) return; // Already running

  logger.info('Cleanup worker started', { intervalMs: CLEANUP_INTERVAL_MS });
  cleanupTimer = setInterval(() => void runCleanup(), CLEANUP_INTERVAL_MS);

  // Don't let the cleanup timer prevent process exit
  cleanupTimer.unref();
}

/**
 * Stops the cleanup worker. Called during graceful shutdown.
 */
export function stopCleanupWorker(): void {
  if (cleanupTimer) {
    clearInterval(cleanupTimer);
    cleanupTimer = null;
    logger.info('Cleanup worker stopped');
  }
}
