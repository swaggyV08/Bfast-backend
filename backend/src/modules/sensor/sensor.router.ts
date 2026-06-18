import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import { uploadBatchController } from './sensor.controller';
import { batchSensorReadingsSchema } from './sensor.schemas';

const router = Router();

router.post('/batch',
  authenticate as RequestHandler,
  validate(batchSensorReadingsSchema),
  uploadBatchController as RequestHandler,
);

export { router as sensorRouter };
