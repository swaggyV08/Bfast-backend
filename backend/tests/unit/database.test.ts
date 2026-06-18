import { query } from '@/database/query';

// Mock the pool so tests don't need a real DB
jest.mock('@/database/pool', () => ({
  pool: {
    query: jest.fn(),
    connect: jest.fn(),
    on: jest.fn(),
    end: jest.fn(),
  },
}));

import { pool } from '@/database/pool';

describe('Database query helper', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('executes a parameterized query successfully', async () => {
    const mockResult = {
      rows: [{ id: '123', email: 'test@test.com' }],
      rowCount: 1,
      command: 'SELECT',
      oid: 0,
      fields: [],
    };

    (pool.query as jest.Mock).mockResolvedValueOnce(mockResult);

    const result = await query('SELECT * FROM users WHERE id = $1', ['123']);

    expect(result.rows).toHaveLength(1);
    expect(result.rows[0]).toHaveProperty('email', 'test@test.com');
    expect(pool.query).toHaveBeenCalledWith(
      'SELECT * FROM users WHERE id = $1',
      ['123'],
    );
  });

  it('throws InternalError when query fails', async () => {
    (pool.query as jest.Mock).mockRejectedValueOnce(new Error('connection refused'));

    await expect(query('SELECT 1')).rejects.toMatchObject({
      errorCode: 'INTERNAL_ERROR',
      statusCode: 500,
    });
  });
});