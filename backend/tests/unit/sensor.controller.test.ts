import { Request, Response, NextFunction } from 'express';
import { uploadBatchController } from '@/modules/sensor/sensor.controller';
import * as sensorService from '@/modules/sensor/sensor.service';

jest.mock('@/modules/sensor/sensor.service');

describe('Sensor Controller', () => {
  let mockReq: any;
  let mockRes: any;
  let mockNext: any;

  beforeEach(() => {
    jest.clearAllMocks();
    mockReq = {
      body: { readings: [] },
      user: { sub: 'user-123' },
      correlationId: 'test-corr-id',
    };
    mockRes = {
      status: jest.fn().mockReturnThis(),
      json: jest.fn(),
    };
    mockNext = jest.fn();
  });

  it('handles batch upload successfully', async () => {
    (sensorService.processBatchReadings as jest.Mock).mockResolvedValue(undefined);

      await uploadBatchController(mockReq, mockRes, mockNext);

      expect(mockNext).not.toHaveBeenCalled();
      expect(sensorService.processBatchReadings).toHaveBeenCalledWith('user-123', mockReq.body, 'test-corr-id');
    expect(mockRes.status).toHaveBeenCalledWith(201);
    expect(mockRes.json).toHaveBeenCalledWith(
      expect.objectContaining({
        success: true,
        data: { message: 'Batch uploaded successfully' },
      })
    );
  });

  it('calls next with error on failure', async () => {
    const error = new Error('Test Error');
    (sensorService.processBatchReadings as jest.Mock).mockRejectedValue(error);

    await uploadBatchController(mockReq, mockRes, mockNext);

    expect(mockNext).toHaveBeenCalledWith(error);
  });
});
