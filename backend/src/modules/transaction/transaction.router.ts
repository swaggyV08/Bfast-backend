import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  createTransactionController,
  relayTransactionController,
  getTransactionController,
  getHistoryController,
} from './transaction.controller';
import { createTransactionSchema, relayTransactionSchema } from './transaction.schemas';

const router = Router();

router.post('/',
  authenticate as RequestHandler,
  validate(createTransactionSchema),
  createTransactionController as RequestHandler,
);

router.post('/relay',
  authenticate as RequestHandler,
  validate(relayTransactionSchema),
  relayTransactionController as RequestHandler,
);

router.get('/history',
  authenticate as RequestHandler,
  getHistoryController as RequestHandler,
);

// Must be AFTER /history to avoid matching 'history' as :txRef
router.get('/:txRef',
  authenticate as RequestHandler,
  getTransactionController as RequestHandler,
);

export { router as transactionRouter };
