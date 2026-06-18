import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  generateSessionController,
  refreshSessionController,
  getActiveSessionController,
} from './session.controller';
import { generateSessionSchema, refreshSessionSchema } from './session.schemas';

const router = Router();

// All session routes require authentication
router.post('/generate',
  authenticate as RequestHandler,
  validate(generateSessionSchema),
  generateSessionController as RequestHandler,
);

router.post('/refresh',
  authenticate as RequestHandler,
  validate(refreshSessionSchema),
  refreshSessionController as RequestHandler,
);

router.get('/active',
  authenticate as RequestHandler,
  getActiveSessionController as RequestHandler,
);

export { router as sessionRouter };
