import { QueryResult, QueryResultRow } from 'pg';
import { pool } from './pool';
import { logger } from '@/shared/utils/logger';
import { InternalError } from '@/shared/errors/AppError';

/**
 * Executes a single SQL query against the pool.
 *
 * Usage:
 *   const result = await query<UserRow>(
 *     'SELECT * FROM users WHERE id = $1',
 *     [userId]
 *   );
 *
 * Why parameterized queries ($1, $2, ...)?
 * They prevent SQL injection — the #1 database vulnerability.
 * Never concatenate user input into SQL strings.
 */
export async function query<T extends QueryResultRow = QueryResultRow>(
  sql: string,
  params: unknown[] = [],
  correlationId?: string,
): Promise<QueryResult<T>> {
  const start = Date.now();

  try {
    const result = await pool.query<T>(sql, params);

    logger.debug('Query executed', {
      correlationId,
      durationMs: Date.now() - start,
      rowCount: result.rowCount,
      // Only log the first 200 chars of the query to avoid logging sensitive data
      query: sql.substring(0, 200),
    });

    return result;
  } catch (error) {
    logger.error('Database query failed', {
      correlationId,
      durationMs: Date.now() - start,
      query: sql.substring(0, 200),
      error: error instanceof Error ? error.message : String(error),
    });

    throw new InternalError('Database operation failed');
  }
}

/**
 * Executes multiple queries in a single transaction.
 * All queries succeed or ALL are rolled back — atomicity guaranteed.
 *
 * Usage:
 *   await transaction(async (client) => {
 *     await client.query('UPDATE wallets SET balance = balance - $1 WHERE id = $2', [amount, senderId]);
 *     await client.query('UPDATE wallets SET balance = balance + $1 WHERE id = $2', [amount, receiverId]);
 *   });
 */
export async function transaction<T>(
  callback: (client: typeof pool) => Promise<T>,
  correlationId?: string,
): Promise<T> {
  const client = await pool.connect();

  try {
    await client.query('BEGIN');

    const result = await callback(client as unknown as typeof pool);

    await client.query('COMMIT');

    logger.debug('Transaction committed', { correlationId });

    return result;
  } catch (error) {
    await client.query('ROLLBACK');

    logger.error('Transaction rolled back', {
      correlationId,
      error: error instanceof Error ? error.message : String(error),
    });

    throw error;
  } finally {
    // ALWAYS release the client, even if an error occurred
    client.release();
  }
}