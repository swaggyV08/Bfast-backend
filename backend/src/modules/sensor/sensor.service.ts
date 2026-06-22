import { logger } from '@/shared/utils/logger';
import * as sensorRepo from './sensor.repository';
import type { BatchSensorReadingsInput } from './sensor.schemas';
import { TapAlignmentEngine } from './TapAlignmentEngine';

export async function processBatchReadings(
  userId: string,
  input: BatchSensorReadingsInput,
  correlationId: string,
): Promise<void> {
  if (input.readings.length === 0) return;

  await sensorRepo.insertSensorReadings(userId, input.readings, correlationId);

  // Send to TapAlignmentEngine for real-time cross-correlation
  TapAlignmentEngine.processBatch(userId, input, correlationId);

  logger.info('Inserted sensor readings batch', {
    correlationId,
    userId,
    count: input.readings.length,
    sessionId: input.sessionId,
    role: input.role
  });
}
