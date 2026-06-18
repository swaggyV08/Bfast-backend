import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import { storeCalibrationController, getCalibrationController } from './calibration.controller';
import { storeCalibrationSchema } from './calibration.schemas';

const router = Router();

router.post('/',
  authenticate as RequestHandler,
  validate(storeCalibrationSchema),
  storeCalibrationController as RequestHandler,
);

router.get('/:deviceId',
  authenticate as RequestHandler,
  getCalibrationController as RequestHandler,
);

export { router as calibrationRouter };
