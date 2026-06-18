/**
 * Base class for all application errors.
 *
 * isOperational = true  → expected error (bad request, not found, etc.) — log as warning
 * isOperational = false → unexpected error (db crash, bug) — log as error, alert team
 */
export class AppError extends Error {
  public readonly statusCode: number;
  public readonly errorCode: string;
  public readonly isOperational: boolean;

  constructor(message: string, statusCode: number, errorCode: string, isOperational = true) {
    super(message);
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.isOperational = isOperational;

    // Restores proper prototype chain (required when extending built-in classes in TypeScript)
    Object.setPrototypeOf(this, new.target.prototype);

    // Captures a clean stack trace pointing to where the error was thrown
    Error.captureStackTrace(this, this.constructor);
  }
}

// 400 — the request body was malformed or failed validation
export class ValidationError extends AppError {
  constructor(message: string) {
    super(message, 400, 'VALIDATION_ERROR');
  }
}

// 401 — missing or invalid JWT token
export class UnauthorizedError extends AppError {
  constructor(message = 'Authentication required') {
    super(message, 401, 'UNAUTHORIZED');
  }
}

// 403 — valid token but no permission for this resource
export class ForbiddenError extends AppError {
  constructor(message = 'Access denied') {
    super(message, 403, 'FORBIDDEN');
  }
}

// 404 — resource not found
export class NotFoundError extends AppError {
  constructor(resource: string) {
    super(`${resource} not found`, 404, 'NOT_FOUND');
  }
}

// 409 — conflict (e.g. duplicate transaction / session already used)
export class ConflictError extends AppError {
  constructor(message: string, errorCode = 'CONFLICT') {
    super(message, 409, errorCode);
  }
}

// 410 — resource is gone (e.g. session code expired)
export class GoneError extends AppError {
  constructor(message: string, errorCode = 'GONE') {
    super(message, 410, errorCode);
  }
}

// 429 — too many requests
export class RateLimitError extends AppError {
  constructor() {
    super('Too many requests. Please slow down.', 429, 'RATE_LIMIT_EXCEEDED');
  }
}

// 500 — something unexpected broke (our fault)
export class InternalError extends AppError {
  constructor(message = 'An unexpected error occurred') {
    super(message, 500, 'INTERNAL_ERROR', false); // isOperational = false → alert team
  }
}
