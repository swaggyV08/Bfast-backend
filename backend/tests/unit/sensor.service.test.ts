import * as sensorRepo from '@/modules/sensor/sensor.repository';
import * as sensorService from '@/modules/sensor/sensor.service';

jest.mock('@/modules/sensor/sensor.repository');
jest.mock('@/shared/utils/logger', () => ({
  logger: {
    info: jest.fn(),
    error: jest.fn(),
    warn: jest.fn(),
    debug: jest.fn(),
  },
}));

describe('Sensor Service', () => {
  const mockRepo = sensorRepo as jest.Mocked<typeof sensorRepo>;
  const correlationId = 'test-corr-id';
  const userId = 'user-123';

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('processes batch readings successfully', async () => {
    mockRepo.insertSensorReadings.mockResolvedValue();

    const input = {
      readings: [
        {
          deviceId: 'dev-1',
          accelX: 0.1,
          accelY: 0.2,
          accelZ: 9.8,
          accelMagnitude: 9.8,
          gyroX: 0.01,
          gyroY: 0.02,
          gyroZ: 0.03,
          gyroMagnitude: 0.04,
          tapDetected: false,
          recordedAt: new Date().toISOString(),
        },
      ],
    };

    await sensorService.processBatchReadings(userId, input, correlationId);

    expect(mockRepo.insertSensorReadings).toHaveBeenCalledWith(userId, input.readings, correlationId);
  });

  it('returns early if batch is empty', async () => {
    const input = { readings: [] };

    await sensorService.processBatchReadings(userId, input, correlationId);

    expect(mockRepo.insertSensorReadings).not.toHaveBeenCalled();
  });
});
