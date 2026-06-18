import { query } from '@/database/query';
import { logger } from '@/shared/utils/logger';

/**
 * Fraud detection result.
 */
export interface FraudCheckResult {
  passed: boolean;
  reason?: string;
  code?: string;
}

// ─── Configurable Limits ───────────────────────────────────────────────────

const MAX_TX_PER_HOUR = 20;             // Max transactions per user per hour
const MAX_DAILY_VOLUME_PAISE = 20000000; // ₹2,00,000 per day (in paise)

// ─── Fraud Checks ──────────────────────────────────────────────────────────

/**
 * Velocity check: limits the number of transactions a user can make per hour.
 * Prevents automated abuse and compromised accounts draining funds quickly.
 */
export async function checkVelocity(
  userId: string,
  correlationId?: string,
): Promise<FraudCheckResult> {
  const result = await query<{ count: string }>(
    `SELECT COUNT(*) as count FROM transactions
     WHERE sender_user_id = $1
       AND initiated_at > NOW() - INTERVAL '1 hour'
       AND status IN ('pending', 'confirmed')`,
    [userId],
    correlationId,
  );

  const count = parseInt(result.rows[0]?.count ?? '0', 10);

  if (count >= MAX_TX_PER_HOUR) {
    logger.warn('Velocity limit exceeded', {
      correlationId,
      userId,
      txCountLastHour: count,
      limit: MAX_TX_PER_HOUR,
    });
    return {
      passed: false,
      reason: `Transaction velocity exceeded: ${count} transactions in the last hour (limit: ${MAX_TX_PER_HOUR})`,
      code: 'VELOCITY_LIMIT_EXCEEDED',
    };
  }

  return { passed: true };
}

/**
 * Per-transaction amount limit check.
 * Uses the user's personal tx_limit_paise (set during KYC or by admin).
 */
export function checkAmountLimits(
  amountPaise: number,
  txLimitPaise: number,
): FraudCheckResult {
  if (amountPaise > txLimitPaise) {
    return {
      passed: false,
      reason: `Amount ₹${(amountPaise / 100).toFixed(2)} exceeds per-transaction limit of ₹${(txLimitPaise / 100).toFixed(2)}`,
      code: 'AMOUNT_LIMIT_EXCEEDED',
    };
  }
  return { passed: true };
}

/**
 * Daily volume check: limits the total amount a user can send per day.
 * Required for RBI compliance and fraud prevention.
 */
export async function checkDailyVolume(
  userId: string,
  amountPaise: number,
  correlationId?: string,
): Promise<FraudCheckResult> {
  const result = await query<{ total: string }>(
    `SELECT COALESCE(SUM(amount_paise), 0) as total FROM transactions
     WHERE sender_user_id = $1
       AND initiated_at > NOW() - INTERVAL '24 hours'
       AND status IN ('pending', 'confirmed')`,
    [userId],
    correlationId,
  );

  const totalToday = parseInt(result.rows[0]?.total ?? '0', 10);
  const projectedTotal = totalToday + amountPaise;

  if (projectedTotal > MAX_DAILY_VOLUME_PAISE) {
    logger.warn('Daily volume limit exceeded', {
      correlationId,
      userId,
      totalTodayPaise: totalToday,
      attemptedPaise: amountPaise,
      limit: MAX_DAILY_VOLUME_PAISE,
    });
    return {
      passed: false,
      reason: `Daily volume exceeded: ₹${(projectedTotal / 100).toFixed(2)} would exceed daily limit of ₹${(MAX_DAILY_VOLUME_PAISE / 100).toFixed(2)}`,
      code: 'DAILY_VOLUME_EXCEEDED',
    };
  }

  return { passed: true };
}

/**
 * Runs all fraud checks in sequence. Short-circuits on first failure.
 */
export async function runAllFraudChecks(
  userId: string,
  amountPaise: number,
  txLimitPaise: number,
  correlationId?: string,
): Promise<FraudCheckResult> {
  // 1. Per-transaction amount limit
  const amountCheck = checkAmountLimits(amountPaise, txLimitPaise);
  if (!amountCheck.passed) return amountCheck;

  // 2. Velocity (tx count per hour)
  const velocityCheck = await checkVelocity(userId, correlationId);
  if (!velocityCheck.passed) return velocityCheck;

  // 3. Daily volume
  const volumeCheck = await checkDailyVolume(userId, amountPaise, correlationId);
  if (!volumeCheck.passed) return volumeCheck;

  return { passed: true };
}
