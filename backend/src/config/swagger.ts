import { OpenAPIV3 } from 'openapi-types';

/**
 * BFast OpenAPI 3.0 Specification
 *
 * Complete API documentation for the BFast tap-to-pay backend.
 * Covers: Auth, Session, Bump Matching, Transactions, Wallet, Calibration, Admin.
 */

export const swaggerSpec: OpenAPIV3.Document = {
  openapi: '3.0.3',
  info: {
    title: 'BFast — Tap-to-Pay API',
    version: '1.0.0',
    description: `
# BFast Backend API

BFast is a tap-to-pay system that allows two users to initiate transactions by physically tapping their phones together, eliminating QR code scanning.

## How It Works

1. **Receiver** generates a BLE session code (broadcast via GATT)
2. **Sender** detects receiver via BLE proximity (RSSI ≤ 15cm)
3. **Sender** submits a bump request with nearby device RSSI readings
4. **Server** matches the bump using RSSI ranking algorithm
5. **Sender** submits a transaction referencing the session code
6. **Server** executes a 13-step validation pipeline and atomic ledger transfer

## Authentication

All endpoints (except \`/auth/register\` and \`/auth/login\`) require a JWT Bearer token in the \`Authorization\` header.

## Rate Limiting

- 100 requests per minute per IP (configurable)
    `,
    contact: {
      name: 'BFast Team',
    },
    license: {
      name: 'Private',
    },
  },
  servers: [
    {
      url: 'http://localhost:3000/api/v1',
      description: 'Local Development',
    },
  ],
  tags: [
    { name: 'Auth', description: 'User registration, login, and JWT management' },
    { name: 'Session', description: 'BLE session code generation and management' },
    { name: 'Bumps', description: 'Sensor-validated bump matching: accelerometer tap detection + gyroscope rejection + BLE RSSI ranking + ±500ms timestamp correlation' },
    { name: 'Transactions', description: 'Payment processing (13-step pipeline)' },
    { name: 'Wallet', description: 'Balance, top-up, and ledger' },
    { name: 'Calibration', description: 'Device sensor calibration profiles' },
    { name: 'Admin', description: 'System administration (requires admin role)' },
    { name: 'Health', description: 'Server health check' },
  ],
  components: {
    securitySchemes: {
      BearerAuth: {
        type: 'http',
        scheme: 'bearer',
        bearerFormat: 'JWT',
        description: 'JWT access token from /auth/login or /auth/register',
      },
    },
    schemas: {
      ApiResponse: {
        type: 'object',
        properties: {
          success: { type: 'boolean', example: true },
          data: { type: 'object' },
          meta: {
            type: 'object',
            properties: {
              timestamp: { type: 'string', format: 'date-time' },
              correlationId: { type: 'string', format: 'uuid' },
            },
          },
        },
      },
      ErrorResponse: {
        type: 'object',
        properties: {
          success: { type: 'boolean', example: false },
          error: {
            type: 'object',
            properties: {
              code: { type: 'string', example: 'VALIDATION_ERROR' },
              message: { type: 'string' },
              details: { type: 'object' },
            },
          },
          meta: {
            type: 'object',
            properties: {
              timestamp: { type: 'string', format: 'date-time' },
              correlationId: { type: 'string', format: 'uuid' },
            },
          },
        },
      },
      RegisterRequest: {
        type: 'object',
        required: ['email', 'password', 'displayName', 'deviceId', 'platform'],
        properties: {
          email: { type: 'string', format: 'email', example: 'user@bfast.app' },
          password: { type: 'string', minLength: 8, example: 'P@ssw0rd!Strong123' },
          displayName: { type: 'string', example: 'Priya Sharma' },
          phoneNumber: { type: 'string', example: '+919876543210' },
          deviceId: { type: 'string', format: 'uuid', example: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890' },
          platform: { type: 'string', enum: ['android', 'ios'], example: 'android' },
        },
      },
      LoginRequest: {
        type: 'object',
        required: ['email', 'password', 'deviceId'],
        properties: {
          email: { type: 'string', format: 'email', example: 'user@bfast.app' },
          password: { type: 'string', example: 'P@ssw0rd!Strong123' },
          deviceId: { type: 'string', format: 'uuid', example: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890' },
        },
      },
      AuthResponse: {
        type: 'object',
        properties: {
          user: {
            type: 'object',
            properties: {
              id: { type: 'string', format: 'uuid' },
              email: { type: 'string' },
              displayName: { type: 'string' },
              phoneNumber: { type: 'string', nullable: true },
              status: { type: 'string', enum: ['active', 'suspended', 'deactivated'] },
              kycVerified: { type: 'boolean' },
            },
          },
          wallet: {
            type: 'object',
            properties: {
              id: { type: 'string', format: 'uuid' },
              balancePaise: { type: 'string' },
              currency: { type: 'string', example: 'INR' },
            },
          },
          tokens: {
            type: 'object',
            properties: {
              accessToken: { type: 'string' },
              refreshToken: { type: 'string' },
              expiresIn: { type: 'integer', example: 900 },
            },
          },
        },
      },
      SessionGenerateRequest: {
        type: 'object',
        required: ['deviceId'],
        properties: {
          deviceId: { type: 'string', format: 'uuid', description: 'Receiver device UUID' },
        },
      },
      SessionResponse: {
        type: 'object',
        properties: {
          scId: { type: 'string', description: 'Session code ID (32 hex chars)', example: '77d6939b0c19559f5599def71a3af2ab' },
          encryptedCode: { type: 'string', description: 'AES-256-GCM encrypted session code for BLE broadcast' },
          expiresAt: { type: 'string', format: 'date-time', description: '30-second TTL' },
        },
      },
      SensorSnapshot: {
        type: 'object',
        required: ['accelPeakMs2', 'accelDurationMs', 'gyroMagnitudeRads', 'tapTimestamp'],
        description: 'Processed sensor data captured at the moment of tap by the Android app. Validated server-side against the device calibration profile.',
        properties: {
          accelPeakMs2: {
            type: 'number',
            minimum: 0,
            maximum: 100,
            example: 8.7,
            description: 'Peak acceleration magnitude at tap (m/s²). A real tap on most Android devices produces 3–20 m/s².',
          },
          accelDurationMs: {
            type: 'integer',
            minimum: 10,
            maximum: 1000,
            example: 85,
            description: 'Duration of the acceleration spike (ms). A genuine tap lasts 30–150ms; a drop or push is longer.',
          },
          gyroMagnitudeRads: {
            type: 'number',
            minimum: 0,
            maximum: 50,
            example: 0.12,
            description: 'Angular velocity magnitude at tap time (rad/s). A stationary phone reads <0.3; waving reads 2–10+.',
          },
          tapTimestamp: {
            type: 'string',
            format: 'date-time',
            example: '2026-06-12T05:30:00.123+05:30',
            description: 'Client-side tap detection timestamp (ISO 8601 with timezone). Used for ±500ms correlation.',
          },
        },
      },
      SenderBumpRequest: {
        type: 'object',
        required: ['deviceId', 'nearbyDevices', 'sensorSnapshot'],
        properties: {
          deviceId: { type: 'string', format: 'uuid', description: 'Sender device UUID' },
          nearbyDevices: {
            type: 'array',
            minItems: 1,
            items: {
              type: 'object',
              required: ['deviceId', 'rssi'],
              properties: {
                deviceId: { type: 'string', description: 'Nearby device UUID' },
                rssi: { type: 'integer', minimum: -120, maximum: 0, example: -45, description: 'RSSI signal strength (dBm)' },
              },
            },
          },
          rssi: { type: 'integer', minimum: -120, maximum: 0, description: 'Optional aggregate RSSI' },
          sensorSnapshot: { $ref: '#/components/schemas/SensorSnapshot' },
        },
      },
      ReceiverBumpRequest: {
        type: 'object',
        required: ['deviceId', 'sensorSnapshot'],
        properties: {
          deviceId: { type: 'string', format: 'uuid', description: 'Receiver device UUID' },
          rssi: { type: 'integer', minimum: -120, maximum: 0, description: 'Optional RSSI reading' },
          sensorSnapshot: { $ref: '#/components/schemas/SensorSnapshot' },
        },
      },
      BumpMatchResponse: {
        type: 'object',
        properties: {
          matched: { type: 'boolean', example: true },
          matchId: { type: 'string', example: 'a4f8c91b2e3d7f06' },
          receiverDeviceId: { type: 'string', format: 'uuid' },
          receiverUserId: { type: 'string', format: 'uuid' },
          rssiScore: { type: 'integer', example: -45 },
          timeDeltaMs: { type: 'integer', example: 127, description: 'Time difference between sender and receiver taps (ms)' },
          sensorValidated: { type: 'boolean', example: true, description: 'Whether sensor data passed calibration validation' },
        },
      },
      CreateTransactionRequest: {
        type: 'object',
        required: ['sessionCodeId', 'receiverDeviceId', 'amountPaise', 'nonce'],
        properties: {
          sessionCodeId: { type: 'string', description: 'Session code ID from BLE handshake' },
          receiverDeviceId: { type: 'string', format: 'uuid' },
          amountPaise: { type: 'integer', minimum: 100, description: 'Amount in paise (100 paise = ₹1)', example: 50000 },
          currency: { type: 'string', default: 'INR', example: 'INR' },
          nonce: { type: 'string', minLength: 16, description: 'Client-generated nonce for replay protection' },
          clientInitiatedAt: { type: 'string', format: 'date-time' },
          rssiAtPayment: { type: 'integer', minimum: -120, maximum: 0, example: -45 },
          idempotencyKey: { type: 'string', maxLength: 64, description: 'Optional idempotency key' },
          note: { type: 'string', maxLength: 200, example: 'Coffee at Chai Stop' },
        },
      },
      TransactionResponse: {
        type: 'object',
        properties: {
          transactionId: { type: 'string', format: 'uuid' },
          txRef: { type: 'string', example: 'tx_20260611_484e6c7dcd2b' },
          status: { type: 'string', enum: ['pending', 'confirmed', 'failed', 'reversed'] },
          amountPaise: { type: 'integer', example: 50000 },
          amountINR: { type: 'string', example: '500.00' },
          currency: { type: 'string', example: 'INR' },
          senderBalance: { type: 'integer', description: 'Sender balance after transfer (paise)' },
          receiverBalance: { type: 'integer', description: 'Receiver balance after transfer (paise)' },
          latencyMs: { type: 'integer', nullable: true, description: 'End-to-end latency in ms' },
          confirmedAt: { type: 'string', format: 'date-time', nullable: true },
        },
      },
      TopupRequest: {
        type: 'object',
        required: ['amountPaise'],
        properties: {
          amountPaise: { type: 'integer', minimum: 100, maximum: 10000000, example: 1000000, description: 'Top-up amount in paise' },
        },
      },
      WalletBalanceResponse: {
        type: 'object',
        properties: {
          walletId: { type: 'string', format: 'uuid' },
          balancePaise: { type: 'integer', example: 950000 },
          balanceINR: { type: 'string', example: '9500.00' },
          currency: { type: 'string', example: 'INR' },
          isFrozen: { type: 'boolean', example: false },
        },
      },
      CalibrationRequest: {
        type: 'object',
        required: ['deviceId'],
        properties: {
          deviceId: { type: 'string', format: 'uuid' },
          tapThresholdMs2: { type: 'number', default: 2.5, description: 'Accelerometer tap threshold (m/s²)' },
          tapDurationMaxMs: { type: 'integer', default: 200, description: 'Max tap duration (ms)' },
          gyroDriftBaselineRads: { type: 'number', default: 0, description: 'Gyroscope drift baseline (rad/s)' },
          gyroRejectionThresholdRads: { type: 'number', default: 0.5, description: 'Gyro rejection threshold (rad/s)' },
          rssi1mCalibrationDbm: { type: 'integer', default: -59, description: 'RSSI at 1m calibration (dBm)' },
          emaAlphaQuiet: { type: 'number', default: 0.5, description: 'EMA alpha for quiet environment' },
          emaAlphaNoisy: { type: 'number', default: 0.12, description: 'EMA alpha for noisy environment' },
          pathLossExponent: { type: 'number', default: 2.0, description: 'BLE path loss exponent' },
        },
      },
      CalibrationResponse: {
        type: 'object',
        properties: {
          deviceId: { type: 'string', format: 'uuid' },
          tapThresholdMs2: { type: 'number' },
          tapDurationMaxMs: { type: 'integer' },
          gyroDriftBaselineRads: { type: 'number' },
          gyroRejectionThresholdRads: { type: 'number' },
          rssi1mCalibrationDbm: { type: 'integer' },
          emaAlphaQuiet: { type: 'number' },
          emaAlphaNoisy: { type: 'number' },
          pathLossExponent: { type: 'number' },
          calibratedAt: { type: 'string', format: 'date-time' },
          txSuccessCount: { type: 'integer' },
        },
      },
      SystemStats: {
        type: 'object',
        properties: {
          totalUsers: { type: 'integer' },
          totalTransactions: { type: 'integer' },
          totalVolumePaise: { type: 'integer' },
          totalVolumeINR: { type: 'string' },
          activeSessions: { type: 'integer' },
          confirmedTransactions: { type: 'integer' },
          failedTransactions: { type: 'integer' },
        },
      },
    },
  },
  paths: {
    // ─── Health ─────────────────────────────────────────────────────────────
    '/../../health': {
      get: {
        tags: ['Health'],
        summary: 'Health check',
        description: 'Returns server health status, version, and environment.',
        operationId: 'healthCheck',
        responses: {
          '200': {
            description: 'Server is healthy',
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  properties: {
                    success: { type: 'boolean', example: true },
                    data: {
                      type: 'object',
                      properties: {
                        status: { type: 'string', example: 'healthy' },
                        version: { type: 'string', example: '1.0.0' },
                        environment: { type: 'string', example: 'development' },
                        timestamp: { type: 'string', format: 'date-time' },
                      },
                    },
                  },
                },
              },
            },
          },
        },
      },
    },

    // ─── Auth ───────────────────────────────────────────────────────────────
    '/auth/register': {
      post: {
        tags: ['Auth'],
        summary: 'Register a new user',
        description: 'Creates user account, device, wallet (₹0 balance), and returns JWT tokens.',
        operationId: 'register',
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/RegisterRequest' } } },
        },
        responses: {
          '201': {
            description: 'User registered successfully',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/AuthResponse' } } }] } } },
          },
          '400': { description: 'Validation error', content: { 'application/json': { schema: { $ref: '#/components/schemas/ErrorResponse' } } } },
          '409': { description: 'Email already taken', content: { 'application/json': { schema: { $ref: '#/components/schemas/ErrorResponse' } } } },
        },
      },
    },
    '/auth/login': {
      post: {
        tags: ['Auth'],
        summary: 'Login',
        description: 'Authenticates user with email + password, returns JWT tokens and wallet info.',
        operationId: 'login',
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/LoginRequest' } } },
        },
        responses: {
          '200': {
            description: 'Login successful',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/AuthResponse' } } }] } } },
          },
          '400': { description: 'Validation error' },
          '401': { description: 'Invalid credentials' },
        },
      },
    },
    '/auth/refresh': {
      post: {
        tags: ['Auth'],
        summary: 'Refresh access token',
        operationId: 'refreshToken',
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { type: 'object', required: ['refreshToken'], properties: { refreshToken: { type: 'string' } } } } },
        },
        responses: {
          '200': { description: 'Tokens refreshed' },
          '401': { description: 'Invalid refresh token' },
        },
      },
    },
    '/auth/me': {
      get: {
        tags: ['Auth'],
        summary: 'Get current user profile',
        operationId: 'getMe',
        security: [{ BearerAuth: [] }],
        responses: {
          '200': { description: 'User profile with wallet' },
          '401': { description: 'Unauthorized' },
        },
      },
    },

    // ─── Session ────────────────────────────────────────────────────────────
    '/session/generate': {
      post: {
        tags: ['Session'],
        summary: 'Generate BLE session code',
        description: 'Receiver generates a 32-byte cryptographic session code (encrypted with AES-256-GCM) for BLE GATT broadcast. TTL: 30 seconds.',
        operationId: 'generateSession',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/SessionGenerateRequest' } } },
        },
        responses: {
          '201': {
            description: 'Session code generated',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/SessionResponse' } } }] } } },
          },
          '401': { description: 'Unauthorized' },
        },
      },
    },
    '/session/refresh': {
      post: {
        tags: ['Session'],
        summary: 'Refresh session code',
        description: 'Expires old session code and generates a new one. Call every ~25 seconds.',
        operationId: 'refreshSession',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/SessionGenerateRequest' } } },
        },
        responses: {
          '200': { description: 'New session code', content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/SessionResponse' } } }] } } } },
        },
      },
    },
    '/session/active': {
      get: {
        tags: ['Session'],
        summary: 'Get active session for device',
        operationId: 'getActiveSession',
        security: [{ BearerAuth: [] }],
        responses: {
          '200': { description: 'Active session or null' },
        },
      },
    },

    // ─── Bumps ──────────────────────────────────────────────────────────────
    '/bumps/sender': {
      post: {
        tags: ['Bumps'],
        summary: 'Submit sender bump (with sensor validation)',
        description: `Sender posts nearby device RSSI readings **plus accelerometer/gyroscope sensor snapshot**.

Server pipeline:
1. **Sensor validation** — accelerometer peak must exceed device's calibrated tap threshold; gyroscope must be below rejection threshold (phone not waving)
2. **Store** sender bump request with full sensor audit trail
3. **RSSI ranking** — rank nearby devices by signal strength (highest = closest)
4. **Timestamp correlation** — only match receivers whose tap timestamp is within ±500ms
5. **Match** — first qualifying device wins; audit trail records RSSI score, time delta, and both devices' accelerometer readings

If sensor validation fails, returns HTTP 400 with the specific rejection reason.`,
        operationId: 'senderBump',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/SenderBumpRequest' } } },
        },
        responses: {
          '200': {
            description: 'Match result (matched: true/false)',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/BumpMatchResponse' } } }] } } },
          },
          '400': { description: 'Sensor validation failed (acceleration too low, gyroscope too high, or tap duration too long)', content: { 'application/json': { schema: { $ref: '#/components/schemas/ErrorResponse' } } } },
        },
      },
    },
    '/bumps/receiver': {
      post: {
        tags: ['Bumps'],
        summary: 'Register receiver bump (with sensor validation)',
        description: `Receiver registers as available for bump matching. Requires sensor snapshot from the phone's accelerometer and gyroscope.

The sensor data is validated against the device's calibration profile and stored so that when a sender arrives, the server can:
- Correlate tap timestamps (±500ms window)
- Include receiver's accelerometer reading in the match audit trail

If sensor validation fails, returns HTTP 400.`,
        operationId: 'receiverBump',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/ReceiverBumpRequest' } } },
        },
        responses: {
          '200': { description: 'Bump registered with sensor validation status' },
          '400': { description: 'Sensor validation failed', content: { 'application/json': { schema: { $ref: '#/components/schemas/ErrorResponse' } } } },
        },
      },
    },
    '/bumps/match/{matchId}': {
      get: {
        tags: ['Bumps'],
        summary: 'Get bump match details (includes sensor audit trail)',
        description: 'Returns match details including RSSI score, timestamp delta between taps, and both devices\' accelerometer readings at match time.',
        operationId: 'getMatch',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'matchId', in: 'path', required: true, schema: { type: 'string' } },
        ],
        responses: {
          '200': { description: 'Match details with sensor audit trail' },
          '404': { description: 'Match not found' },
        },
      },
    },

    // ─── Transactions ───────────────────────────────────────────────────────
    '/transactions': {
      post: {
        tags: ['Transactions'],
        summary: 'Create transaction (tap-to-pay)',
        description: `Executes a payment through the **13-step validation pipeline**:

1. Idempotency check
2. Nonce uniqueness (replay protection)
3. Session code validation
4. Sender account validation
5. Receiver account validation
6. Self-payment check
7. Wallet status check (frozen?)
8. Balance validation
9. Fraud & velocity limit checks
10. Atomic ledger transfer (PostgreSQL \`process_transfer()\`)
11. Consume session code (single-use)
12. Confirm transaction + record latency
13. Increment calibration success count`,
        operationId: 'createTransaction',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/CreateTransactionRequest' } } },
        },
        responses: {
          '201': {
            description: 'Transaction confirmed',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/TransactionResponse' } } }] } } },
          },
          '400': { description: 'Validation error (insufficient balance, expired session, etc.)' },
          '403': { description: 'Fraud check failed or wallet frozen' },
          '409': { description: 'Nonce reused (replay attack)' },
        },
      },
    },
    '/transactions/relay': {
      post: {
        tags: ['Transactions'],
        summary: 'Relay transaction (from GATT write)',
        description: 'Receiver relays the encrypted payment intent received via BLE GATT write.',
        operationId: 'relayTransaction',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: {
            'application/json': {
              schema: {
                type: 'object',
                required: ['sessionCodeId', 'senderUserId', 'senderDeviceId', 'amountPaise', 'nonce'],
                properties: {
                  sessionCodeId: { type: 'string' },
                  senderUserId: { type: 'string', format: 'uuid' },
                  senderDeviceId: { type: 'string', format: 'uuid' },
                  amountPaise: { type: 'integer', minimum: 100 },
                  currency: { type: 'string', default: 'INR' },
                  nonce: { type: 'string', minLength: 16 },
                  rssiAtPayment: { type: 'integer' },
                  idempotencyKey: { type: 'string' },
                  note: { type: 'string' },
                },
              },
            },
          },
        },
        responses: {
          '201': { description: 'Transaction confirmed' },
        },
      },
    },
    '/transactions/history': {
      get: {
        tags: ['Transactions'],
        summary: 'Get transaction history',
        operationId: 'getHistory',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'page', in: 'query', schema: { type: 'integer', default: 1 } },
          { name: 'limit', in: 'query', schema: { type: 'integer', default: 20, maximum: 100 } },
        ],
        responses: {
          '200': { description: 'Paginated transaction list' },
        },
      },
    },
    '/transactions/{txRef}': {
      get: {
        tags: ['Transactions'],
        summary: 'Get transaction by reference',
        operationId: 'getTransaction',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'txRef', in: 'path', required: true, schema: { type: 'string' }, example: 'tx_20260611_484e6c7dcd2b' },
        ],
        responses: {
          '200': { description: 'Transaction details' },
          '403': { description: 'Not authorized to view' },
          '404': { description: 'Transaction not found' },
        },
      },
    },

    // ─── Wallet ─────────────────────────────────────────────────────────────
    '/wallet/balance': {
      get: {
        tags: ['Wallet'],
        summary: 'Get wallet balance',
        operationId: 'getBalance',
        security: [{ BearerAuth: [] }],
        responses: {
          '200': {
            description: 'Current balance',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/WalletBalanceResponse' } } }] } } },
          },
        },
      },
    },
    '/wallet/topup': {
      post: {
        tags: ['Wallet'],
        summary: 'Top up wallet (dev only)',
        description: 'Add funds to wallet. **Disabled in production.** For development/testing only.',
        operationId: 'topup',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/TopupRequest' } } },
        },
        responses: {
          '200': { description: 'New balance after top-up', content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/WalletBalanceResponse' } } }] } } } },
        },
      },
    },
    '/wallet/ledger': {
      get: {
        tags: ['Wallet'],
        summary: 'Get ledger entries',
        description: 'Paginated double-entry ledger for the authenticated user.',
        operationId: 'getLedger',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'page', in: 'query', schema: { type: 'integer', default: 1 } },
          { name: 'limit', in: 'query', schema: { type: 'integer', default: 20, maximum: 100 } },
        ],
        responses: {
          '200': { description: 'Paginated ledger entries' },
        },
      },
    },

    // ─── Calibration ────────────────────────────────────────────────────────
    '/calibration': {
      post: {
        tags: ['Calibration'],
        summary: 'Store device calibration profile',
        description: 'Upserts sensor tuning parameters for tap detection. Values are used to personalize the tap detection algorithm per device.',
        operationId: 'storeCalibration',
        security: [{ BearerAuth: [] }],
        requestBody: {
          required: true,
          content: { 'application/json': { schema: { $ref: '#/components/schemas/CalibrationRequest' } } },
        },
        responses: {
          '200': {
            description: 'Calibration stored',
            content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/CalibrationResponse' } } }] } } },
          },
        },
      },
    },
    '/calibration/{deviceId}': {
      get: {
        tags: ['Calibration'],
        summary: 'Get calibration profile',
        operationId: 'getCalibration',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'deviceId', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } },
        ],
        responses: {
          '200': { description: 'Calibration profile or null' },
        },
      },
    },

    // ─── Admin ──────────────────────────────────────────────────────────────
    '/admin/stats': {
      get: {
        tags: ['Admin'],
        summary: 'System statistics',
        description: 'Dashboard metrics: total users, transactions, volume, active sessions.',
        operationId: 'getStats',
        security: [{ BearerAuth: [] }],
        responses: {
          '200': { description: 'System stats', content: { 'application/json': { schema: { allOf: [{ $ref: '#/components/schemas/ApiResponse' }, { type: 'object', properties: { data: { $ref: '#/components/schemas/SystemStats' } } }] } } } },
          '403': { description: 'Admin access required' },
        },
      },
    },
    '/admin/transactions': {
      get: {
        tags: ['Admin'],
        summary: 'List all transactions',
        operationId: 'adminGetTransactions',
        security: [{ BearerAuth: [] }],
        parameters: [
          { name: 'page', in: 'query', schema: { type: 'integer', default: 1 } },
          { name: 'limit', in: 'query', schema: { type: 'integer', default: 20 } },
          { name: 'status', in: 'query', schema: { type: 'string', enum: ['pending', 'confirmed', 'failed', 'reversed'] } },
        ],
        responses: {
          '200': { description: 'All transactions (paginated)' },
          '403': { description: 'Admin access required' },
        },
      },
    },
    '/admin/users/{id}/suspend': {
      post: {
        tags: ['Admin'],
        summary: 'Suspend a user',
        operationId: 'suspendUser',
        security: [{ BearerAuth: [] }],
        parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } }],
        responses: {
          '200': { description: 'User suspended' },
          '403': { description: 'Admin access required' },
        },
      },
    },
    '/admin/users/{id}/activate': {
      post: {
        tags: ['Admin'],
        summary: 'Activate a user',
        operationId: 'activateUser',
        security: [{ BearerAuth: [] }],
        parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } }],
        responses: {
          '200': { description: 'User activated' },
          '403': { description: 'Admin access required' },
        },
      },
    },
    '/admin/wallets/{id}/freeze': {
      post: {
        tags: ['Admin'],
        summary: 'Freeze a wallet',
        operationId: 'freezeWallet',
        security: [{ BearerAuth: [] }],
        parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } }],
        responses: {
          '200': { description: 'Wallet frozen' },
          '403': { description: 'Admin access required' },
        },
      },
    },
    '/admin/wallets/{id}/unfreeze': {
      post: {
        tags: ['Admin'],
        summary: 'Unfreeze a wallet',
        operationId: 'unfreezeWallet',
        security: [{ BearerAuth: [] }],
        parameters: [{ name: 'id', in: 'path', required: true, schema: { type: 'string', format: 'uuid' } }],
        responses: {
          '200': { description: 'Wallet unfrozen' },
          '403': { description: 'Admin access required' },
        },
      },
    },
  },
};
