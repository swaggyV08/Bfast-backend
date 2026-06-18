const fs = require('fs');
const path = require('path');
const { Client } = require('pg');
require('dotenv').config();

async function run() {
  const client = new Client({
    host: process.env.DB_HOST,
    port: process.env.DB_PORT,
    database: process.env.DB_NAME,
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
  });

  try {
    await client.connect();
    console.log('Connected to DB');

    const files = [
      '014-mobile-auth.sql',
      '015-sensor-readings.sql'
    ];

    for (const file of files) {
      const filePath = path.join(__dirname, 'database', 'migrations', file);
      const sql = fs.readFileSync(filePath, 'utf8');
      console.log(`Executing ${file}...`);
      await client.query(sql);
      console.log(`Successfully executed ${file}`);
    }
  } catch (err) {
    console.error('Error applying migrations:', err);
    process.exit(1);
  } finally {
    await client.end();
  }
}

run();
