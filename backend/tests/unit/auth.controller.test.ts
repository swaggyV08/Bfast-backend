import { Request, Response, NextFunction } from 'express';
import { mobileRegisterController, mobileLoginController, registerController, loginController } from '@/modules/auth/auth.controller';
import * as authService from '@/modules/auth/auth.service';

jest.mock('@/modules/auth/auth.service');

describe('Auth Controller', () => {
  let mockReq: any;
  let mockRes: any;
  let mockNext: any;

  beforeEach(() => {
    jest.clearAllMocks();
    mockRes = {
      status: jest.fn().mockReturnThis(),
      json: jest.fn(),
    };
    mockNext = jest.fn();
  });

  describe('mobileRegisterController', () => {
    it('registers user and returns 201', async () => {
      const mockReq = {
        body: { phoneNumber: '+919876543210' },
        correlationId: 'test-corr-id',
        ip: '127.0.0.1',
        headers: { 'user-agent': 'jest' },
      } as unknown as Request;

      (authService.mobileRegister as jest.Mock).mockResolvedValue({
        user: { id: 'user-1' },
        wallet: { balancePaise: 100000 },
        tokens: { accessToken: 'token' },
      });

      await mobileRegisterController(mockReq, mockRes, mockNext);

      expect(mockNext).not.toHaveBeenCalled();
      expect(authService.mobileRegister).toHaveBeenCalled();
      expect(mockRes.status).toHaveBeenCalledWith(201);
      expect(mockRes.json).toHaveBeenCalledWith(expect.objectContaining({ success: true }));
    });
  });

  describe('mobileLoginController', () => {
    it('logs in user and returns 200', async () => {
      const mockReq = {
        body: { phoneNumber: '+919876543210' },
        correlationId: 'test-corr-id',
        ip: '127.0.0.1',
        headers: { 'user-agent': 'jest' },
      } as unknown as Request;

      (authService.mobileLogin as jest.Mock).mockResolvedValue({
        user: { id: 'user-1' },
        wallet: { balancePaise: 100000 },
        tokens: { accessToken: 'token' },
      });

      await mobileLoginController(mockReq, mockRes, mockNext);

      expect(mockNext).not.toHaveBeenCalled();
      expect(authService.mobileLogin).toHaveBeenCalled();
      expect(mockRes.status).toHaveBeenCalledWith(200);
      expect(mockRes.json).toHaveBeenCalledWith(expect.objectContaining({ success: true }));
    });
  });
});
