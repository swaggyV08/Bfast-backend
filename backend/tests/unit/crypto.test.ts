import {
  encryptAES256GCM,
  decryptAES256GCM,
  generateSecureRandom,
} from '@/shared/utils/crypto';

describe('AES-256-GCM Encryption', () => {
  it('encrypts and decrypts plaintext correctly', () => {
    const plaintext = 'Hello, B-Fast session code!';
    const encrypted = encryptAES256GCM(plaintext);
    const decrypted = decryptAES256GCM(encrypted);

    expect(decrypted).toBe(plaintext);
  });

  it('produces different ciphertext for the same plaintext (unique IV)', () => {
    const plaintext = 'same input twice';
    const encrypted1 = encryptAES256GCM(plaintext);
    const encrypted2 = encryptAES256GCM(plaintext);

    // Different IVs → different ciphertext
    expect(encrypted1).not.toBe(encrypted2);

    // Both decrypt to same plaintext
    expect(decryptAES256GCM(encrypted1)).toBe(plaintext);
    expect(decryptAES256GCM(encrypted2)).toBe(plaintext);
  });

  it('detects tampered ciphertext (auth tag validation)', () => {
    const plaintext = 'sensitive data';
    const encrypted = encryptAES256GCM(plaintext);

    // Tamper with the ciphertext (flip a character in the middle)
    const tampered = encrypted.slice(0, 20) + 'X' + encrypted.slice(21);

    expect(() => decryptAES256GCM(tampered)).toThrow();
  });

  it('handles empty string', () => {
    const encrypted = encryptAES256GCM('');
    const decrypted = decryptAES256GCM(encrypted);
    expect(decrypted).toBe('');
  });

  it('handles long payloads', () => {
    const longPayload = JSON.stringify({
      sender_id: generateSecureRandom(16),
      receiver_id: generateSecureRandom(16),
      session_code: generateSecureRandom(32),
      amount: 10000,
      currency: 'INR',
      nonce: generateSecureRandom(16),
      timestamp: new Date().toISOString(),
    });

    const encrypted = encryptAES256GCM(longPayload);
    const decrypted = decryptAES256GCM(encrypted);
    expect(decrypted).toBe(longPayload);
    expect(JSON.parse(decrypted)).toHaveProperty('sender_id');
  });

  it('handles unicode characters', () => {
    const plaintext = '₹500 paid to चाय स्टॉप 🎉';
    const encrypted = encryptAES256GCM(plaintext);
    const decrypted = decryptAES256GCM(encrypted);
    expect(decrypted).toBe(plaintext);
  });
});

describe('generateSecureRandom', () => {
  it('generates hex strings of correct length', () => {
    const random32 = generateSecureRandom(32);
    expect(random32).toHaveLength(64); // 32 bytes = 64 hex chars

    const random16 = generateSecureRandom(16);
    expect(random16).toHaveLength(32);
  });

  it('generates unique values', () => {
    const values = new Set(Array.from({ length: 100 }, () => generateSecureRandom()));
    expect(values.size).toBe(100);
  });
});
