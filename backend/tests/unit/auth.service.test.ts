import { ConflictError, UnauthorizedError } from '@/shared/errors/AppError';

// Mock the repository so tests don't need a real DB
jest.mock('@/modules/auth/auth.repository');
jest.mock('@/shared/utils/crypto');

import * as authRepo from '@/modules/auth/auth.repository';
import * as crypto from '@/shared/utils/crypto';
import * as authService from '@/modules/auth/auth.service';

const mockRepo = authRepo as jest.Mocked<typeof authRepo>;
const mockCrypto = crypto as jest.Mocked<typeof crypto>;

describe('Auth Service', () => {
  const correlationId = 'test-correlation-id';
  const meta = { ipAddress: '127.0.0.1', userAgent: 'Jest' };

  beforeEach(() => jest.clearAllMocks());

  describe('register', () => {
    const input = {
      email: 'test@bfast.com',
      password: 'SecurePass1',
      displayName: 'Test User',
      deviceId: '550e8400-e29b-41d4-a716-446655440000',
      platform: 'android' as const,
    };

    it('creates user, wallet, and device on success', async () => {
      mockRepo.findUserByEmail.mockResolvedValue(null);
      mockCrypto.hashPassword.mockResolvedValue('hashed_password');
      mockRepo.createUser.mockResolvedValue({
        id: 'user-123',
        email: input.email,
        display_name: input.displayName,
        phone_number: null,
        status: 'active',
        kyc_verified: false,
        cognito_sub: 'user-123',
        tx_limit_paise: 1000000,
        created_at: new Date(),
        updated_at: new Date(),
        last_login_at: null,
        deleted_at: null,
      });
      mockRepo.storePasswordHash.mockResolvedValue(undefined);
      mockRepo.createWallet.mockResolvedValue({
        id: 'wallet-123',
        user_id: 'user-123',
        wallet_type: 'MAIN',
        balance_paise: 0,
        currency: 'INR',
        is_frozen: false,
        created_at: new Date(),
        updated_at: new Date(),
      });
      mockRepo.upsertDevice.mockResolvedValue({} as never);
      mockRepo.storeRefreshToken.mockResolvedValue('token-id');
      mockCrypto.issueAccessToken.mockReturnValue('access_token');
      mockCrypto.issueRefreshToken.mockReturnValue('refresh_token');
      mockCrypto.hashRefreshToken.mockReturnValue('hashed_refresh');

      const result = await authService.register(input, correlationId, meta);

      expect(result.user.email).toBe(input.email);
      expect(result.wallet.balancePaise).toBe(0);
      expect(result.tokens.accessToken).toBe('access_token');
      expect(mockRepo.createUser).toHaveBeenCalledTimes(1);
      expect(mockRepo.createWallet).toHaveBeenCalledTimes(1);
    });

    it('throws ConflictError if email already exists', async () => {
      mockRepo.findUserByEmail.mockResolvedValue({ id: 'existing' } as never);

      await expect(authService.register(input, correlationId, meta))
        .rejects.toThrow(ConflictError);
    });
  });

  describe('login', () => {
    it('throws UnauthorizedError for wrong password', async () => {
      mockRepo.findUserByEmail.mockResolvedValue({
        id: 'user-123',
        status: 'active',
        email: 'test@bfast.com',
        display_name: 'Test',
        phone_number: null,
        kyc_verified: false,
      } as never);
      mockRepo.getPasswordHash.mockResolvedValue('hashed_password');
      mockCrypto.verifyPassword.mockResolvedValue(false);

      await expect(
        authService.login(
          { email: 'test@bfast.com', password: 'WrongPass1', deviceId: '550e8400-e29b-41d4-a716-446655440000' },
          correlationId,
          meta,
        ),
      ).rejects.toThrow(UnauthorizedError);
    });

    it('throws UnauthorizedError for non-existent user', async () => {
      mockRepo.findUserByEmail.mockResolvedValue(null);

      await expect(
        authService.login(
          { email: 'nobody@bfast.com', password: 'Pass1234', deviceId: '550e8400-e29b-41d4-a716-446655440000' },
          correlationId,
          meta,
        ),
      ).rejects.toThrow(UnauthorizedError);
    });
  });

  describe('mobileRegister', () => {
    const mobileInput = {
      phoneNumber: '+919876543210',
      displayName: 'Mobile User',
      passcode: '1234',
      confirmPasscode: '1234',
      deviceId: 'mobile-device-uuid',
      platform: 'android' as const,
    };

    it('creates mobile user and initializes wallet with 1000 rupees', async () => {
      mockRepo.findUserByPhoneNumber.mockResolvedValue(null);
      mockCrypto.hashPassword.mockResolvedValue('hashed_passcode');
      mockRepo.createUser.mockResolvedValue({
        id: 'm-user-1',
        display_name: mobileInput.displayName,
        phone_number: mobileInput.phoneNumber,
        status: 'active',
        kyc_verified: false,
        cognito_sub: 'm-user-1',
        tx_limit_paise: 1000000,
        created_at: new Date(),
        updated_at: new Date(),
        last_login_at: null,
        deleted_at: null,
      });
      mockRepo.storePasscodeHash.mockResolvedValue();
      mockRepo.createWallet.mockResolvedValue({
        id: 'm-wallet-1',
        user_id: 'm-user-1',
        wallet_type: 'MAIN',
        balance_paise: 0,
        currency: 'INR',
        is_frozen: false,
        created_at: new Date(),
        updated_at: new Date(),
      });
      mockRepo.setWalletBalance.mockResolvedValue();
      mockRepo.upsertDevice.mockResolvedValue({} as never);
      mockCrypto.issueAccessToken.mockReturnValue('access');
      mockCrypto.issueRefreshToken.mockReturnValue('refresh');
      
      const result = await authService.mobileRegister(mobileInput, correlationId, meta);
      
      expect(result.user.phoneNumber).toBe(mobileInput.phoneNumber);
      expect(result.wallet.balancePaise).toBe(100000);
      expect(mockRepo.createUser).toHaveBeenCalled();
      expect(mockRepo.createWallet).toHaveBeenCalled();
      expect(mockRepo.setWalletBalance).toHaveBeenCalledWith('m-wallet-1', 100000, correlationId);
    });
  });

  describe('mobileLogin', () => {
    const loginInput = {
      phoneNumber: '+919876543210',
      passcode: '1234',
      deviceId: 'mobile-device-uuid',
    };

    it('authenticates mobile user and issues tokens', async () => {
      mockRepo.findUserByPhoneNumber.mockResolvedValue({
        id: 'm-user-1',
        status: 'active',
        email: null,
        display_name: 'Mobile',
        phone_number: loginInput.phoneNumber,
        kyc_verified: false,
      } as never);
      mockRepo.getPasscodeHash.mockResolvedValue('hashed_passcode');
      mockCrypto.verifyPassword.mockResolvedValue(true);
      mockRepo.getWalletByUserId.mockResolvedValue({
        id: 'm-wallet-1',
        balance_paise: 100000,
        currency: 'INR',
      } as never);

      const result = await authService.mobileLogin(loginInput, correlationId, meta);
      expect(result.user.phoneNumber).toBe(loginInput.phoneNumber);
      expect(result.wallet.balancePaise).toBe(100000);
    });
  });
});