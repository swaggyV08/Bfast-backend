$ErrorActionPreference = "Stop"
$B = "http://localhost:3000/api/v1"

# Login (slow part - bcrypt)
$s = Invoke-RestMethod "$B/auth/login" -Method POST -Body '{"email":"sender@bfast.test","password":"P@ssw0rd!Strong123","deviceId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890"}' -ContentType "application/json"
$r = Invoke-RestMethod "$B/auth/login" -Method POST -Body '{"email":"receiver@bfast.test","password":"P@ssw0rd!Strong123","deviceId":"b2c3d4e5-f6a7-8901-bcde-f12345678901"}' -ContentType "application/json"
$st = $s.data.tokens.accessToken
$rt = $r.data.tokens.accessToken
Write-Host "Logged in. Sender balance: $($s.data.wallet.balancePaise) paise"

# Bump match
Invoke-RestMethod "$B/bumps/receiver" -Method POST -Body '{"deviceId":"b2c3d4e5-f6a7-8901-bcde-f12345678901","rssi":-48}' -ContentType "application/json" -Headers @{Authorization="Bearer $rt"} | Out-Null
$sb = Invoke-RestMethod "$B/bumps/sender" -Method POST -Body '{"deviceId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","nearbyDevices":[{"deviceId":"b2c3d4e5-f6a7-8901-bcde-f12345678901","rssi":-45}]}' -ContentType "application/json" -Headers @{Authorization="Bearer $st"}
Write-Host "Bump matched=$($sb.data.matched) rssi=$($sb.data.rssiScore)"

# Session + Transaction (must happen within 30s)
$ses = Invoke-RestMethod "$B/session/generate" -Method POST -Body '{"deviceId":"b2c3d4e5-f6a7-8901-bcde-f12345678901"}' -ContentType "application/json" -Headers @{Authorization="Bearer $rt"}
Write-Host "Session=$($ses.data.scId)"

$nonce = [guid]::NewGuid().ToString("N")
$txBody = '{"sessionCodeId":"' + $ses.data.scId + '","receiverDeviceId":"b2c3d4e5-f6a7-8901-bcde-f12345678901","amountPaise":50000,"currency":"INR","nonce":"' + $nonce + '","rssiAtPayment":-45,"note":"Coffee at Chai Stop"}'
$tx = Invoke-RestMethod "$B/transactions" -Method POST -Body $txBody -ContentType "application/json" -Headers @{Authorization="Bearer $st"}

Write-Host ""
Write-Host "=== TRANSACTION CONFIRMED ===" -ForegroundColor Green
Write-Host "TX Ref:   $($tx.data.txRef)"
Write-Host "Amount:   Rs $(($tx.data.amountPaise / 100).ToString('F2'))"
Write-Host "Status:   $($tx.data.status)"
Write-Host "Latency:  $($tx.data.latencyMs)ms"
Write-Host "Sender:   Rs $(($tx.data.senderBalance / 100).ToString('F2'))"
Write-Host "Receiver: Rs $(($tx.data.receiverBalance / 100).ToString('F2'))"

# Verify
$sb2 = Invoke-RestMethod "$B/wallet/balance" -Method GET -Headers @{Authorization="Bearer $st"}
$rb2 = Invoke-RestMethod "$B/wallet/balance" -Method GET -Headers @{Authorization="Bearer $rt"}
Write-Host ""
Write-Host "=== BALANCES VERIFIED ===" -ForegroundColor Green
Write-Host "Sender final:   Rs $($sb2.data.balanceINR)"
Write-Host "Receiver final: Rs $($rb2.data.balanceINR)"

$hist = Invoke-RestMethod "$B/transactions/history?page=1&limit=5" -Method GET -Headers @{Authorization="Bearer $st"}
Write-Host "Transaction history: $($hist.data.total) total"

$led = Invoke-RestMethod "$B/wallet/ledger?page=1&limit=5" -Method GET -Headers @{Authorization="Bearer $st"}
Write-Host "Ledger entries: $($led.data.total) total"
