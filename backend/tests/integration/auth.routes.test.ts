import request from 'supertest';
import { createApp } from '@/app';

// Mock DB so integration tests don't need a live Postgres
jest.mock('@/database/query', () => ({
  query: jest.fn(),
  transaction: jest.fn(),
}));

jest.mock('@/modules/auth/auth.repository');
jest.mock('@/shared/utils/crypto');

import * as authRepo from '@/modules/auth/auth.repository';
import * as crypto from '@/shared/utils/crypto';

const mockRepo = authRepo as jest.Mocked<typeof authRepo>;
const mockCrypto = crypto as jest.Mocked<typeof crypto>;

describe('Auth Routes — Integration', () => {
  const app = createApp();

  describe('POST /api/v1/auth/register', () => {
    const validBody = {
      email: 'priya@bfast.com',
      password: 'Secure@123',
      displayName: 'Priya K',
      deviceId: '550e8400-e29b-41d4-a716-446655440000',
      platform: 'android',
    };

    it('returns 201 on valid registration', async () => {
      mockRepo.findUserByEmail.mockResolvedValue(null);
      mockCrypto.hashPassword.mockResolvedValue('hashed');
      mockRepo.createUser.mockResolvedValue({ id: 'u1', email: validBody.email, display_name: 'Priya K', phone_number: null, status: 'active', kyc_verified: false, cognito_sub: 'u1', tx_limit_paise: 1000000, created_at: new Date(), updated_at: new Date(), last_login_at: null, deleted_at: null });
      mockRepo.storePasswordHash.mockResolvedValue(undefined);
      mockRepo.createWallet.mockResolvedValue({ id: 'w1', user_id: 'u1', wallet_type: 'MAIN', balance_paise: 0, currency: 'INR', is_frozen: false, created_at: new Date(), updated_at: new Date() });
      mockRepo.upsertDevice.mockResolvedValue({} as never);
      mockRepo.storeRefreshToken.mockResolvedValue('rt1');
      mockCrypto.issueAccessToken.mockReturnValue('access');
      mockCrypto.issueRefreshToken.mockReturnValue('refresh');
      mockCrypto.hashRefreshToken.mockReturnValue('hash');

      const res = await request(app).post('/api/v1/auth/register').send(validBody);

      expect(res.status).toBe(201);
      expect(res.body.success).toBe(true);
      expect(res.body.data.tokens.accessToken).toBe('access');
    });

    it('returns 400 for invalid email', async () => {
      const res = await request(app)
        .post('/api/v1/auth/register')
        .send({ ...validBody, email: 'not-an-email' });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('VALIDATION_ERROR');
      expect(res.body.error.details).toHaveProperty('email');
    });

    it('returns 400 for weak password', async () => {
      const res = await request(app)
        .post('/api/v1/auth/register')
        .send({ ...validBody, password: 'weak' });

      expect(res.status).toBe(400);
      expect(res.body.error.details).toHaveProperty('password');
    });

    it('returns 409 for duplicate email', async () => {
      mockRepo.findUserByEmail.mockResolvedValue({ id: 'existing' } as never);
      mockCrypto.hashPassword.mockResolvedValue('hashed');

      const res = await request(app).post('/api/v1/auth/register').send(validBody);

      expect(res.status).toBe(409);
      expect(res.body.error.code).toBe('EMAIL_TAKEN');
    });
  });

  describe('POST /api/v1/auth/login', () => {
    it('returns 400 for missing password', async () => {
      const res = await request(app)
        .post('/api/v1/auth/login')
        .send({ email: 'test@bfast.com', deviceId: '550e8400-e29b-41d4-a716-446655440000' });

      expect(res.status).toBe(400);
    });
  });

  describe('GET /api/v1/auth/me', () => {
    it('returns 401 without Authorization header', async () => {
      const res = await request(app).get('/api/v1/auth/me');

      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('UNAUTHORIZED');
    });
  });
});