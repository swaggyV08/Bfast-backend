import { Router, RequestHandler } from 'express';
import { authenticate } from '@/middleware/authenticate';
import { isAdmin } from '@/middleware/isAdmin';
import {
  getStatsController,
  getTransactionsController,
  suspendUserController,
  activateUserController,
  freezeWalletController,
  unfreezeWalletController,
} from './admin.controller';

const router = Router();

// All admin routes require authentication + admin role
const adminGuard: RequestHandler[] = [
  authenticate as RequestHandler,
  isAdmin as RequestHandler,
];

router.get('/stats', ...adminGuard, getStatsController as RequestHandler);
router.get('/transactions', ...adminGuard, getTransactionsController as RequestHandler);
router.post('/users/:id/suspend', ...adminGuard, suspendUserController as RequestHandler);
router.post('/users/:id/activate', ...adminGuard, activateUserController as RequestHandler);
router.post('/wallets/:id/freeze', ...adminGuard, freezeWalletController as RequestHandler);
router.post('/wallets/:id/unfreeze', ...adminGuard, unfreezeWalletController as RequestHandler);

export { router as adminRouter };
