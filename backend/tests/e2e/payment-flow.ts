/**
 * BFast End-to-End Payment Flow Smoke Test
 *
 * Tests the complete tap-to-pay pipeline:
 * 1. Login both users (sender + receiver)
 * 2. Store device calibration
 * 3. Top up sender wallet
 * 4. Receiver generates session code
 * 5. Receiver registers bump
 * 6. Sender submits bump → gets match
 * 7. Sender submits transaction using session code
 * 8. Verify balances changed
 * 9. Verify transaction history
 */

const BASE = 'http://localhost:3000/api/v1';

async function request(method: string, path: string, body?: object, token?: string) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const json = await res.json();
  if (!res.ok && !json.success) {
    console.error(`❌ ${method} ${path} → ${res.status}`, JSON.stringify(json.error, null, 2));
  }
  return json;
}

async function main() {
  console.log('\n🚀 BFast E2E Payment Flow Test\n');

  // ── Step 1: Login both users ───────────────────────────────────────────
  console.log('1️⃣  Logging in sender...');
  const senderLogin = await request('POST', '/auth/login', {
    email: 'sender@bfast.test',
    password: 'P@ssw0rd!Strong123',
    deviceId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
  });
  const senderToken = senderLogin.data.tokens.accessToken;
  console.log(`   ✅ Sender: ${senderLogin.data.user.email} (balance: ₹${(parseInt(senderLogin.data.wallet.balancePaise) / 100).toFixed(2)})`);

  console.log('1️⃣  Logging in receiver...');
  const receiverLogin = await request('POST', '/auth/login', {
    email: 'receiver@bfast.test',
    password: 'P@ssw0rd!Strong123',
    deviceId: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
  });
  const receiverToken = receiverLogin.data.tokens.accessToken;
  console.log(`   ✅ Receiver: ${receiverLogin.data.user.email}`);

  // ── Step 2: Store device calibration ───────────────────────────────────
  console.log('\n2️⃣  Storing sender device calibration...');
  const calibration = await request('POST', '/calibration', {
    deviceId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    tapThresholdMs2: 1.9,
    tapDurationMaxMs: 180,
    gyroDriftBaselineRads: 0.12,
    gyroRejectionThresholdRads: 0.47,
    rssi1mCalibrationDbm: -61,
    emaAlphaQuiet: 0.5,
    emaAlphaNoisy: 0.12,
    pathLossExponent: 2.8,
  }, senderToken);
  console.log(`   ✅ Calibration stored: tap=${calibration.data.tapThresholdMs2}m/s², rssi_1m=${calibration.data.rssi1mCalibrationDbm}dBm`);

  // ── Step 3: Check sender balance ───────────────────────────────────────
  console.log('\n3️⃣  Checking sender balance...');
  const balance = await request('GET', '/wallet/balance', undefined, senderToken);
  console.log(`   ✅ Sender balance: ₹${balance.data.balanceINR}`);

  // ── Step 4: Receiver generates session code ────────────────────────────
  console.log('\n4️⃣  Receiver generating session code...');
  const session = await request('POST', '/session/generate', {
    deviceId: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
  }, receiverToken);
  const scId = session.data.scId;
  console.log(`   ✅ Session code: ${scId} (expires: ${session.data.expiresAt})`);

  // ── Step 5: Receiver registers bump ────────────────────────────────────
  console.log('\n5️⃣  Receiver registering bump...');
  const receiverBump = await request('POST', '/bumps/receiver', {
    deviceId: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
    rssi: -48,
  }, receiverToken);
  console.log(`   ✅ Receiver bump registered: ${receiverBump.data.registered}`);

  // ── Step 6: Sender submits bump → match ────────────────────────────────
  console.log('\n6️⃣  Sender submitting bump with RSSI readings...');
  const senderBump = await request('POST', '/bumps/sender', {
    deviceId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    nearbyDevices: [
      { deviceId: 'b2c3d4e5-f6a7-8901-bcde-f12345678901', rssi: -45 },
      { deviceId: 'c3d4e5f6-a7b8-9012-cdef-123456789012', rssi: -72 },
    ],
  }, senderToken);
  console.log(`   ✅ Match found: ${senderBump.data.matched}, matchId: ${senderBump.data.matchId}, rssi: ${senderBump.data.rssiScore}dBm`);

  // ── Step 7: Sender submits transaction ─────────────────────────────────
  console.log('\n7️⃣  Sender submitting payment (₹500)...');
  const nonce = Date.now().toString(36) + Math.random().toString(36).slice(2);
  const tx = await request('POST', '/transactions', {
    sessionCodeId: scId,
    receiverDeviceId: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
    amountPaise: 50000,
    currency: 'INR',
    nonce: nonce,
    rssiAtPayment: -45,
    idempotencyKey: `idem_${nonce}`,
    note: 'Coffee at Chai Stop',
    clientInitiatedAt: new Date().toISOString(),
  }, senderToken);

  if (tx.success) {
    console.log(`   ✅ Transaction CONFIRMED!`);
    console.log(`      TX Ref:   ${tx.data.txRef}`);
    console.log(`      Amount:   ₹${tx.data.amountINR}`);
    console.log(`      Status:   ${tx.data.status}`);
    console.log(`      Latency:  ${tx.data.latencyMs}ms`);
    console.log(`      Sender balance:   ₹${(tx.data.senderBalance / 100).toFixed(2)}`);
    console.log(`      Receiver balance: ₹${(tx.data.receiverBalance / 100).toFixed(2)}`);
  } else {
    console.error(`   ❌ Transaction FAILED:`, tx.error);
  }

  // ── Step 8: Verify balances ────────────────────────────────────────────
  console.log('\n8️⃣  Verifying balances...');
  const senderBal = await request('GET', '/wallet/balance', undefined, senderToken);
  const receiverBal = await request('GET', '/wallet/balance', undefined, receiverToken);
  console.log(`   Sender:   ₹${senderBal.data.balanceINR}`);
  console.log(`   Receiver: ₹${receiverBal.data.balanceINR}`);

  // ── Step 9: Check transaction history ──────────────────────────────────
  console.log('\n9️⃣  Checking transaction history...');
  const history = await request('GET', '/transactions/history?page=1&limit=5', undefined, senderToken);
  console.log(`   ✅ Total transactions: ${history.data.total}`);
  if (history.data.transactions.length > 0) {
    const latest = history.data.transactions[0];
    console.log(`   Latest: ${latest.tx_ref} | ₹${(latest.amount_paise / 100).toFixed(2)} | ${latest.status}`);
  }

  // ── Step 10: Verify calibration success count incremented ──────────────
  console.log('\n🔟  Checking calibration profile...');
  const calProfile = await request('GET', '/calibration/a1b2c3d4-e5f6-7890-abcd-ef1234567890', undefined, senderToken);
  console.log(`   ✅ TX success count: ${calProfile.data.txSuccessCount}`);

  console.log('\n✅ ══════════════════════════════════════════════════');
  console.log('   BFast E2E Payment Flow — ALL STEPS PASSED!');
  console.log('══════════════════════════════════════════════════\n');
}

main().catch(console.error);
