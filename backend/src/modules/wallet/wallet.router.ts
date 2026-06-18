import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import { getBalanceController, topupController, getLedgerController } from './wallet.controller';
import { topupSchema } from './wallet.schemas';

const router = Router();

router.get('/balance',
  authenticate as RequestHandler,
  getBalanceController as RequestHandler,
);

router.post('/topup',
  authenticate as RequestHandler,
  validate(topupSchema),
  topupController as RequestHandler,
);

router.get('/ledger',
  authenticate as RequestHandler,
  getLedgerController as RequestHandler,
);

export { router as walletRouter };
