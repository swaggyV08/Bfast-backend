import { Client } from 'pg';

async function run() {
  const client = new Client({
    host: 'localhost',
    port: 5433,
    user: 'bfast_user',
    password: 'bfast_dev_password',
    database: 'bfast_dev',
  });
  
  await client.connect();
  // We already created 016-nullable-password.sql, let's manually apply it.
  // The db-migrate can just see it as unapplied later, or we can just leave it as is.
  await client.query("ALTER TABLE user_passwords ALTER COLUMN password_hash DROP NOT NULL;");
  
  // Also create a dummy entry in migrations table so db-migrate knows it's applied
  await client.query("INSERT INTO migrations (name, run_on) VALUES ('/016-nullable-password.sql', NOW()) ON CONFLICT DO NOTHING;");
  
  console.log('Migration applied manually.');
  await client.end();
}

run().catch(console.error);
