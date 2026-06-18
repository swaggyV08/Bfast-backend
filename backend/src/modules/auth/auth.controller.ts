import { Request, Response, NextFunction } from 'express';
import * as authService from './auth.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { RegisterInput, LoginInput, MobileRegisterInput, MobileLoginInput, RefreshInput, LogoutInput } from './auth.schemas';

/**
 * Controllers handle HTTP: extract input, call service, format response.
 * They contain NO business logic — that lives in the service.
 *
 * Every method follows this pattern:
 *   1. Extract validated data from req.body (already validated by middleware)
 *   2. Extract metadata (IP, user agent, correlationId)
 *   3. Call service method
 *   4. Return standardized ApiResponse
 *   5. Pass errors to next() → caught by global errorHandler
 */

export async function registerController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as RegisterInput;
    const correlationId = (req as AuthenticatedRequest).correlationId;

    const result = await authService.register(input, correlationId, {
      ipAddress: req.ip,
      userAgent: req.headers['user-agent'],
    });

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(201).json(response);
  } catch (error) {
    next(error);
  }
}

export async function mobileRegisterController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as MobileRegisterInput;
    const correlationId = (req as AuthenticatedRequest).correlationId;

    const result = await authService.mobileRegister(input, correlationId, {
      ipAddress: req.ip,
      userAgent: req.headers['user-agent'],
    });

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(201).json(response);
  } catch (error) {
    next(error);
  }
}

export async function loginController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as LoginInput;
    const correlationId = (req as AuthenticatedRequest).correlationId;

    const result = await authService.login(input, correlationId, {
      ipAddress: req.ip,
      userAgent: req.headers['user-agent'],
    });

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function mobileLoginController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as MobileLoginInput;
    const correlationId = (req as AuthenticatedRequest).correlationId;

    const result = await authService.mobileLogin(input, correlationId, {
      ipAddress: req.ip,
      userAgent: req.headers['user-agent'],
    });

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function refreshController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as RefreshInput;
    const correlationId = (req as AuthenticatedRequest).correlationId;

    const tokens = await authService.refresh(input, correlationId, {
      ipAddress: req.ip,
      userAgent: req.headers['user-agent'],
    });

    const response: ApiResponse<typeof tokens> = {
      success: true,
      data: tokens,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function logoutController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as LogoutInput;
    const authReq = req as AuthenticatedRequest;

    await authService.logout(input, authReq.user.sub, authReq.correlationId);

    const response: ApiResponse = {
      success: true,
      data: { message: 'Logged out successfully' },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function meController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const { findUserById, getWalletByUserId } = await import('./auth.repository');

    const [user, wallet] = await Promise.all([
      findUserById(authReq.user.sub, authReq.correlationId),
      getWalletByUserId(authReq.user.sub, authReq.correlationId),
    ]);

    if (!user) {
      res.status(404).json({ success: false, error: { code: 'NOT_FOUND', message: 'User not found' } });
      return;
    }

    const response: ApiResponse = {
      success: true,
      data: {
        user: {
          id: user.id,
          email: user.email,
          displayName: user.display_name,
          phoneNumber: user.phone_number,
          status: user.status,
          kycVerified: user.kyc_verified,
          createdAt: user.created_at,
          lastLoginAt: user.last_login_at,
        },
        wallet: wallet
          ? {
              id: wallet.id,
              balancePaise: wallet.balance_paise,
              balanceINR: (wallet.balance_paise / 100).toFixed(2),
              currency: wallet.currency,
              isFrozen: wallet.is_frozen,
            }
          : null,
      },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}