import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  createTransactionController,
  relayTransactionController,
  getTransactionController,
  getHistoryController,
  commitTransactionController,
  reverseTransactionController,
} from './transaction.controller';
import { createTransactionSchema, relayTransactionSchema, commitTransactionSchema, reverseTransactionSchema } from './transaction.schemas';

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

router.post('/commit',
  authenticate as RequestHandler,
  validate(commitTransactionSchema),
  commitTransactionController as RequestHandler,
);

router.post('/reverse',
  authenticate as RequestHandler,
  validate(reverseTransactionSchema),
  reverseTransactionController as RequestHandler,
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
