import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  senderBumpController,
  receiverBumpController,
  getMatchController,
} from './bump.controller';
import { senderBumpSchema, receiverBumpSchema } from './bump.schemas';

const router = Router();

router.post('/sender',
  authenticate as RequestHandler,
  validate(senderBumpSchema),
  senderBumpController as RequestHandler,
);

router.post('/receiver',
  authenticate as RequestHandler,
  validate(receiverBumpSchema),
  receiverBumpController as RequestHandler,
);

router.get('/match/:matchId',
  authenticate as RequestHandler,
  getMatchController as RequestHandler,
);

export { router as bumpRouter };
