import dotenv from 'dotenv';
import path from 'path';

//load environment variables from .env file
dotenv.config({ path: path.resolve(__dirname, '../../.env')});

/**
 * Retrieves a required environment variable.
 * Throws an startup if the variable is missing so it fails fast and fails loud.
 */

function requireEnv(key: string): string{
    const value = process.env[key];
    if (!value){
        throw new Error(`
            Missing required environment variable: ${key}`)
    }
    return value;
}

/**
 * Retrives an optional environment variable 
 */
function optionalEnv(key: string, defaultValue: string): string {
    return process.env[key] ?? defaultValue;
}

export const env ={
    //Server
    NODE_ENV: optionalEnv('NODE_ENV', 'development'),
    PORT: parseInt(optionalEnv('PORT', '3000')),
    API_VERSION: optionalEnv('API_VERSION', 'v1'),
    IS_PRODUCTION: process.env['NODE_ENV'] === 'production',
    IS_TEST: process.env['NODE_ENV'] === 'test', 

    //Database
    DB_HOST: optionalEnv('DB_HOST', 'localhost'),
    DB_PORT: parseInt(optionalEnv('DB_PORT', '5432')),
    DB_NAME: optionalEnv('DB_NAME','bfast_dev'),
    DB_USER: optionalEnv('DB_USER', 'bfast_user'),
    DB_PASSWORD: optionalEnv('DB_PASSWORD', 'bfast_dev_password'),
    DB_SSL: optionalEnv('DB_SSL', 'false') === 'true',

    //JWT
    JWT_SECRET: requireEnv('JWT_SECRET'),
    JWT_EXPIRES_IN: optionalEnv('JWT_EXPIRES_IN', '15m'),
    JWT_REFRESH_EXPIRES_IN: optionalEnv('JWT_REFRESH_EXPIRES_IN', '7d'),

    //Encryption
    AES_KEY_HEX: requireEnv('AES_KEY_HEX'),

    //AWS
    AWS_REGION: optionalEnv('AWS_REGION', 'ap-south-1'),
    AWS_COGNITO_USER_POOL_ID: optionalEnv('AWS_COGNITO_USER_POOL_ID',''),
    AWS_COGNITO_CLIENT_ID: optionalEnv('AWS_COGNITO_CLIENT_ID',''),

    //Rate Limiting
    RATE_LIMIT_WINDOW_MS: parseInt(optionalEnv('RATE_LIMIT_WINDOW_MS', '60000'), 10),
    RATE_LIMIT_MAX_REQUESTS: parseInt(optionalEnv('RATE_LIMIT_MAX_REQUESTS', '100'), 10),

    //CORS
    ALLOWED_ORIGINS: optionalEnv('ALLOWED_ORIGINS', 'http://localhost:3001').split(','),
} as const;


