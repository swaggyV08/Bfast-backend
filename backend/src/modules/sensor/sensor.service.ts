import { logger } from '@/shared/utils/logger';
import * as sensorRepo from './sensor.repository';
import type { BatchSensorReadingsInput } from './sensor.schemas';

export async function processBatchReadings(
  userId: string,
  input: BatchSensorReadingsInput,
  correlationId: string,
): Promise<void> {
  if (input.readings.length === 0) return;

  await sensorRepo.insertSensorReadings(userId, input.readings, correlationId);

  logger.info('Inserted sensor readings batch', {
    correlationId,
    userId,
    count: input.readings.length,
  });
}
