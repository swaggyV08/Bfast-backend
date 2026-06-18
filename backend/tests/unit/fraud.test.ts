import { checkAmountLimits } from '@/shared/utils/fraud';

describe('Fraud Detection — Amount Limits', () => {
  it('passes when amount is within limit', () => {
    const result = checkAmountLimits(50000, 500000); // ₹500 vs ₹5000 limit
    expect(result.passed).toBe(true);
  });

  it('fails when amount exceeds per-transaction limit', () => {
    const result = checkAmountLimits(600000, 500000); // ₹6000 vs ₹5000 limit
    expect(result.passed).toBe(false);
    expect(result.code).toBe('AMOUNT_LIMIT_EXCEEDED');
  });

  it('passes when amount exactly equals limit', () => {
    const result = checkAmountLimits(500000, 500000);
    expect(result.passed).toBe(true);
  });

  it('includes human-readable amounts in error message', () => {
    const result = checkAmountLimits(1000000, 500000);
    expect(result.passed).toBe(false);
    expect(result.reason).toContain('₹10000.00');
    expect(result.reason).toContain('₹5000.00');
  });
});
