import { query } from '@/database/query';
import type { SensorReadingInput } from './sensor.schemas';

export async function insertSensorReadings(
  userId: string,
  readings: SensorReadingInput[],
  correlationId?: string,
): Promise<void> {
  if (readings.length === 0) return;

  // Build a bulk insert query:
  // INSERT INTO sensor_readings (user_id, device_id, accel_x, ...) VALUES ($1, $2, ...), ($12, $13, ...)
  
  const values: unknown[] = [];
  const placeholders: string[] = [];
  
  readings.forEach((reading, index) => {
    const offset = index * 12;
    placeholders.push(`($${offset + 1}, $${offset + 2}, $${offset + 3}, $${offset + 4}, $${offset + 5}, $${offset + 6}, $${offset + 7}, $${offset + 8}, $${offset + 9}, $${offset + 10}, $${offset + 11}, $${offset + 12})`);
    
    values.push(
      userId,
      reading.deviceId,
      reading.accelX,
      reading.accelY,
      reading.accelZ,
      reading.accelMagnitude,
      reading.gyroX,
      reading.gyroY,
      reading.gyroZ,
      reading.gyroMagnitude,
      reading.tapDetected,
      reading.tapConfidence ?? null,
    );
  });

  const sql = `
    INSERT INTO sensor_readings (
      user_id, device_id, accel_x, accel_y, accel_z, accel_magnitude,
      gyro_x, gyro_y, gyro_z, gyro_magnitude, tap_detected, tap_confidence
    ) VALUES ${placeholders.join(', ')}
  `;

  await query(sql, values, correlationId);
}
