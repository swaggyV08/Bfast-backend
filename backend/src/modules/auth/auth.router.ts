import { Router, RequestHandler } from 'express';
import { validate } from '@/middleware/validate';
import { authenticate } from '@/middleware/authenticate';
import {
  registerController,
  mobileRegisterController,
  loginController,
  mobileLoginController,
  refreshController,
  logoutController,
  meController,
} from './auth.controller';
import {
  registerSchema,
  mobileRegisterSchema,
  loginSchema,
  mobileLoginSchema,
  refreshSchema,
  logoutSchema,
} from './auth.schemas';

const router = Router();

// Public routes — no auth needed
router.post('/register', validate(registerSchema), registerController as RequestHandler);
router.post('/mobile/register', validate(mobileRegisterSchema), mobileRegisterController as RequestHandler);
router.post('/login',    validate(loginSchema),    loginController as RequestHandler);
router.post('/mobile/login', validate(mobileLoginSchema), mobileLoginController as RequestHandler);
router.post('/refresh',  validate(refreshSchema),  refreshController as RequestHandler);

// Protected routes — cast authenticate to RequestHandler to satisfy TypeScript
// The actual runtime behaviour is correct — authenticate attaches req.user before
// the controller runs. The cast only tells TypeScript "trust me, this is compatible."
router.post('/logout',
  authenticate as RequestHandler,
  validate(logoutSchema),
  logoutController as RequestHandler,
);

router.get('/me',
  authenticate as RequestHandler,
  meController as RequestHandler,
);

export { router as authRouter };