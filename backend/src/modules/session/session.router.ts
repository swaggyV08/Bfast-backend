import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  generateSessionController,
  refreshSessionController,
  getActiveSessionController,
  correlateTapController,
  getSessionStatusController,
  reportTapController,
  pollTapController,
} from './session.controller';
import { generateSessionSchema, refreshSessionSchema, correlateTapSchema, tapEventSchema } from './session.schemas';

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

router.post('/correlate',
  authenticate as RequestHandler,
  validate(correlateTapSchema),
  correlateTapController as RequestHandler,
);

router.get('/status/:sessionId',
  authenticate as RequestHandler,
  getSessionStatusController as RequestHandler,
);

// ── Tap event endpoints (receiver → backend → sender polling) ────────────
router.post('/tap-event',
  authenticate as RequestHandler,
  validate(tapEventSchema),
  reportTapController as RequestHandler,
);

router.get('/tap-poll',
  authenticate as RequestHandler,
  pollTapController as RequestHandler,
);

export { router as sessionRouter };
