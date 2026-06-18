import request from 'supertest';
import { createApp } from '@/app';

/**
 * Tests for the core app scaffolding.
 * These verify that Day 1's work is correct before Day 2 builds on top of it.
 */
describe('App scaffold', () => {
  const app = createApp();

  describe('GET /health', () => {
    it('returns 200 with healthy status', async () => {
      const res = await request(app).get('/health');

      expect(res.status).toBe(200);
      expect(res.body.success).toBe(true);
      expect(res.body.data.status).toBe('healthy');
    });

    it('includes environment and timestamp in health response', async () => {
      const res = await request(app).get('/health');

      expect(res.body.data).toHaveProperty('environment');
      expect(res.body.data).toHaveProperty('timestamp');
      expect(res.body.data).toHaveProperty('version');
    });
  });

  describe('Security headers', () => {
    it('sets X-Content-Type-Options header', async () => {
      const res = await request(app).get('/health');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
    });

    it('sets X-Frame-Options header', async () => {
      const res = await request(app).get('/health');
      expect(res.headers['x-frame-options']).toBeDefined();
    });
  });

  describe('Correlation ID', () => {
    it('returns X-Correlation-ID header on every response', async () => {
      const res = await request(app).get('/health');
      expect(res.headers['x-correlation-id']).toBeDefined();
    });

    it('echoes back a provided X-Correlation-ID', async () => {
      const testId = 'test-correlation-123';
      const res = await request(app).get('/health').set('X-Correlation-ID', testId);
      expect(res.headers['x-correlation-id']).toBe(testId);
    });
  });

  describe('404 handler', () => {
    it('returns 404 with NOT_FOUND code for unknown routes', async () => {
      const res = await request(app).get('/api/v1/nonexistent');

      expect(res.status).toBe(404);
      expect(res.body.success).toBe(false);
      expect(res.body.error.code).toBe('NOT_FOUND');
    });
  });

  describe('CORS', () => {
    it('allows requests with no origin (mobile apps)', async () => {
      const res = await request(app).get('/health');
      expect(res.status).toBe(200);
    });
  });
});