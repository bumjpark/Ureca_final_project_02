# k6 Redis Recovery Test Runner (Docker-based)
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " [Step 1] Initializing Test Environment..." -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

# 1. Reset MySQL Tables
docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -e "
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE coupon_history;
TRUNCATE TABLE coupon_issue;
TRUNCATE TABLE reconciliation_log;
TRUNCATE TABLE verification_report;
TRUNCATE TABLE queue_join_log;
SET FOREIGN_KEY_CHECKS = 1;
UPDATE coupon_policy SET total_quantity = 10000, open_at = '2026-08-01 00:00:00', close_at = '2026-12-31 23:59:59' WHERE id = 1;
"

# 2. Reset Redis
docker exec -i coupon-redis redis-cli FLUSHALL

# 3. Trigger initial recovery / initialization for Policy 1
Write-Host "Triggering initial recovery for Policy 1 (Stock: 10,000)..."
Start-Sleep -Seconds 1
try {
    $initRes = Invoke-RestMethod -Uri "http://localhost:8080/api/coupons/1/recover" -Method Post
    Write-Host "Init Response: " ($initRes | ConvertTo-Json -Compress) -ForegroundColor Green
} catch {
    Write-Host "Init API call note: $_" -ForegroundColor Yellow
}

$initialStock = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
Write-Host "Initial Redis Stock for Policy 1: $initialStock" -ForegroundColor Green

# 4. Clean up old k6 container & logs
docker rm -f k6-runner 2>$null
Remove-Item -Force "k6/summary.json" -ErrorAction SilentlyContinue

Write-Host "`n======================================================" -ForegroundColor Cyan
Write-Host " [Step 2] Starting k6 Redis Recovery Load Test..." -ForegroundColor Cyan
Write-Host " 20,000 VUs ramp-up over 60s against Policy 1 (Stock: 10,000)" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

$workspacePath = "c:/ureka/git/Ureca_final_project_02/k6"

# Start k6 in docker as a background process
$k6Process = Start-Process -FilePath "docker" `
    -ArgumentList "run", "--name", "k6-runner", "--rm", `
        "--add-host=host.docker.internal:host-gateway", `
        "-v", "${workspacePath}:/scripts", `
        "-e", "BASE_URL=http://host.docker.internal:8080", `
        "-e", "POLICY_ID=1", `
        "grafana/k6:latest", "run", "--summary-export=/scripts/summary.json", "/scripts/k6-redis-recovery-test.js" `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput "k6/k6_output.log" `
    -RedirectStandardError "k6/k6_error.log"

$startTime = Get-Date
$redisFailureInjected = $false
$recoveryChecked = $false

while (-not $k6Process.HasExited) {
    Start-Sleep -Seconds 1
    $elapsed = [int]((Get-Date) - $startTime).TotalSeconds

    # Timeline injection at T=20s
    if ($elapsed -ge 20 -and -not $redisFailureInjected) {
        $redisFailureInjected = $true
        Write-Host "`n[T = ${elapsed}s] >>> INJECTING REDIS FAILURE & COMPLETE DATA LOSS! <<<" -ForegroundColor Red
        
        # Check DB count before crash
        $dbCountBefore = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
        $redisStockBefore = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
        Write-Host "[Before Crash] DB Issued Count: $dbCountBefore | Redis Stock: $redisStockBefore" -ForegroundColor Yellow
        
        # Flush Redis completely to simulate total catastrophic loss
        docker exec -i coupon-redis redis-cli FLUSHALL
        Write-Host "[Crash Simulated] Redis FLUSHALL executed! All cache/ZSET/stock wiped." -ForegroundColor Red
    }

    # Monitor / Check Recovery at T >= 25s
    if ($elapsed -ge 25 -and -not $recoveryChecked) {
        $stockAfter = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
        if ($stockAfter -and $stockAfter -ne "") {
            $recoveryChecked = $true
            $dbCountNow = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
            Write-Host "`n[T = ${elapsed}s] >>> REDIS AUTO-RECOVERY DETECTED! <<<" -ForegroundColor Green
            Write-Host "[Recovered State] DB Issued: $dbCountNow | Redis Rebuilt Stock: $stockAfter" -ForegroundColor Green
        }
    }

    if ($elapsed % 10 -eq 0) {
        $currentDb = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
        $currentStock = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
        Write-Host "[T = ${elapsed}s] Elapsed... Current DB Issues: $currentDb | Redis Stock: $currentStock" -ForegroundColor Gray
    }
}

Write-Host "`n======================================================" -ForegroundColor Cyan
Write-Host " [Step 3] k6 Load Test Completed! Analyzing Results..." -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

# Wait 5 seconds for Kafka consumer to drain any final messages
Write-Host "Waiting 5s for Kafka consumer to finalize remaining messages..."
Start-Sleep -Seconds 5

# Final Verification
Write-Host "`n------------------------------------------------------" -ForegroundColor Yellow
Write-Host " [Final Database & Redis Consistency Check]" -ForegroundColor Yellow
Write-Host "------------------------------------------------------" -ForegroundColor Yellow

$finalDbIssues = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
$distinctUsers = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(DISTINCT user_id) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
$historyCount = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_history;").Trim()
$finalRedisStock = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
$finalRedisIssued = (docker exec -i coupon-redis redis-cli scard coupon:policy:1:issued).Trim()

Write-Host "Total Target Quantity : 10000"
Write-Host "DB coupon_issue Count : $finalDbIssues" -ForegroundColor $(if ($finalDbIssues -eq "10000") { "Green" } else { "Red" })
Write-Host "Distinct User Count   : $distinctUsers" -ForegroundColor $(if ($distinctUsers -eq "10000") { "Green" } else { "Red" })
Write-Host "DB coupon_history     : $historyCount" -ForegroundColor $(if ($historyCount -eq "10000") { "Green" } else { "Red" })
Write-Host "Redis Remaining Stock : $finalRedisStock" -ForegroundColor $(if ($finalRedisStock -eq "0") { "Green" } else { "Yellow" })
Write-Host "Redis Issued Set Card : $finalRedisIssued" -ForegroundColor $(if ($finalRedisIssued -eq "10000") { "Green" } else { "Yellow" })

Write-Host "`n------------------------------------------------------" -ForegroundColor Yellow
Write-Host " [k6 Summary & Metrics]" -ForegroundColor Yellow
Write-Host "------------------------------------------------------" -ForegroundColor Yellow
if (Test-Path "k6/k6_output.log") {
    Get-Content "k6/k6_output.log"
}
if (Test-Path "k6/k6_error.log") {
    $errContent = Get-Content "k6/k6_error.log"
    if ($errContent) {
        Write-Host "k6 stderr:" -ForegroundColor Magenta
        $errContent | Select-Object -First 20
    }
}

if (Test-Path "k6/summary.json") {
    $summary = Get-Content "k6/summary.json" | ConvertFrom-Json
    Write-Host "`nk6 Summary Metrics:" -ForegroundColor Cyan
    $metrics = $summary.metrics
    if ($metrics) {
        Write-Host ("  issue_success       : " + $metrics.issue_success.values.count)
        Write-Host ("  issue_error         : " + $metrics.issue_error.values.count)
        Write-Host ("  join_waiting        : " + $metrics.join_waiting.values.count)
        Write-Host ("  join_sold_out       : " + $metrics.join_sold_out.values.count)
        Write-Host ("  join_duplicate      : " + $metrics.join_duplicate.values.count)
        Write-Host ("  poll_sold_out       : " + $metrics.poll_sold_out.values.count)
        Write-Host ("  poll_timeout        : " + $metrics.poll_timeout.values.count)
        Write-Host ("  poll_not_found      : " + $metrics.poll_not_found.values.count)
        Write-Host ("  admitted_total      : " + $metrics.admitted_total.values.count)
        if ($metrics.time_to_admit_ms) {
            Write-Host ("  time_to_admit_avg   : " + [math]::Round($metrics.time_to_admit_ms.values.avg, 2) + " ms")
            Write-Host ("  time_to_admit_p95   : " + [math]::Round($metrics.time_to_admit_ms.values.'p(95)', 2) + " ms")
        }
        if ($metrics.end_to_end_ms) {
            Write-Host ("  end_to_end_avg      : " + [math]::Round($metrics.end_to_end_ms.values.avg, 2) + " ms")
            Write-Host ("  end_to_end_p95      : " + [math]::Round($metrics.end_to_end_ms.values.'p(95)', 2) + " ms")
        }
    }
}

Write-Host "`n------------------------------------------------------" -ForegroundColor Yellow
Write-Host " [Running Final Verification API]" -ForegroundColor Yellow
Write-Host "------------------------------------------------------" -ForegroundColor Yellow
try {
    $verifyRes = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/verification/run?policyId=1&force=true" -Method Post
    Write-Host "Verification Batch Dispatched: " ($verifyRes | ConvertTo-Json -Depth 3) -ForegroundColor Green
    Start-Sleep -Seconds 3
    $reports = Invoke-RestMethod -Uri "http://localhost:8080/api/admin/verification/reports?policyId=1" -Method Get
    Write-Host "Verification Report: " ($reports | ConvertTo-Json -Depth 5) -ForegroundColor Green
} catch {
    Write-Host "Verification API Call: $_" -ForegroundColor Yellow
}
