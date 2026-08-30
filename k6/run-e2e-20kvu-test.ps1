# ==============================================================================
# e2e_20kvu_rampup_test.js 자동화 실행 및 성능/정합성 분석 스크립트
# ==============================================================================
$ErrorActionPreference = "Stop"

Write-Host "======================================================" -ForegroundColor Cyan
Write-Host " [Step 1] Initializing Test Environment for E2E 20k VU Test..." -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

# 1. Truncate MySQL tables
docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -e "SET FOREIGN_KEY_CHECKS = 0; TRUNCATE TABLE coupon_history; DELETE FROM coupon_issue; TRUNCATE TABLE verification_report; TRUNCATE TABLE queue_join_log; SET FOREIGN_KEY_CHECKS = 1;"

# 2. Flush Redis
docker exec -i coupon-redis redis-cli FLUSHALL

# 3. Ensure Policy 1 has 10,000 stock in DB & Redis
docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -e "UPDATE coupon_policy SET total_quantity = 10000, open_at = '2026-08-01 00:00:00', close_at = '2026-12-31 23:59:59' WHERE id = 1;"

Write-Host "Triggering initial recovery for Policy 1 (Stock: 10,000)..." -ForegroundColor Yellow
$initRes = curl.exe -s -X POST "http://localhost:8080/api/coupons/1/recover"
Write-Host "Init Response: " $initRes -ForegroundColor Green

$initStock = docker exec -i coupon-redis redis-cli get coupon:policy:1:stock
Write-Host "Initial Redis Stock for Policy 1: $initStock" -ForegroundColor Green

Write-Host "`n======================================================" -ForegroundColor Cyan
Write-Host " [Step 2] Starting k6 E2E 20,000 VUs Ramp-up Test..." -ForegroundColor Cyan
Write-Host " 20,000 VUs ramp-up over 60s against Policy 1 (Stock: 10,000)" -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

$k6Process = Start-Process -FilePath "docker" -ArgumentList "run --name k6-runner --rm --add-host=host.docker.internal:host-gateway -v `"${PSScriptRoot}:/scripts`" -e TARGET_HOST=http://host.docker.internal:8080 -e POLICY_ID=1 grafana/k6:latest run --summary-export=/scripts/e2e_summary.json /scripts/e2e_20kvu_rampup_test.js" -NoNewWindow -PassThru -RedirectStandardOutput "${PSScriptRoot}/k6_e2e_output.log" -RedirectStandardError "${PSScriptRoot}/k6_e2e_error.log"

$startTime = [DateTime]::UtcNow
$t = 0

while (-not $k6Process.HasExited) {
    Start-Sleep -Seconds 5
    $t = [int]([DateTime]::UtcNow - $startTime).TotalSeconds

    $dbCount = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
    $rStock = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
    $rQueue = (docker exec -i coupon-redis redis-cli zcard coupon:policy:1:queue).Trim()
    Write-Host "[T = ${t}s] DB Issues: $dbCount | Redis Stock: $rStock | Queue Size: $rQueue" -ForegroundColor DarkGray

    if ($t -ge 150) {
        Write-Host "Reached timeout threshold (150s), stopping k6 container..." -ForegroundColor Yellow
        docker rm -f k6-runner 2>$null
        break
    }
}

Write-Host "`n======================================================" -ForegroundColor Cyan
Write-Host " [Step 3] k6 Load Test Completed! Finalizing Results..." -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan

# Consumer 소화 대기
Write-Host "Waiting for Kafka consumer to finalize remaining messages..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

Write-Host "`n------------------------------------------------------" -ForegroundColor Yellow
Write-Host " [Final Database & Redis Consistency Check]" -ForegroundColor Yellow
Write-Host "------------------------------------------------------" -ForegroundColor Yellow

$finalIssues = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
$finalDistinct = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(DISTINCT user_id) FROM coupon_issue WHERE coupon_policy_id = 1;").Trim()
$finalHistory = (docker exec -i coupon-mysql mysql -ucoupon_user -p1234 coupon_db -N -e "SELECT COUNT(*) FROM coupon_history;").Trim()
$finalStock = (docker exec -i coupon-redis redis-cli get coupon:policy:1:stock).Trim()
$finalIssuedSet = (docker exec -i coupon-redis redis-cli scard coupon:policy:1:issued).Trim()

Write-Host "Total Target Quantity : 10000" -ForegroundColor White
Write-Host "DB coupon_issue Count : $finalIssues" -ForegroundColor $(if ($finalIssues -eq 10000) { "Green" } else { "Yellow" })
Write-Host "Distinct User Count   : $finalDistinct" -ForegroundColor $(if ($finalDistinct -eq $finalIssues) { "Green" } else { "Red" })
Write-Host "DB coupon_history     : $finalHistory" -ForegroundColor $(if ($finalHistory -eq $finalIssues) { "Green" } else { "Red" })
Write-Host "Redis Remaining Stock : $finalStock" -ForegroundColor White
Write-Host "Redis Issued Set Card : $finalIssuedSet" -ForegroundColor White

Write-Host "`n------------------------------------------------------" -ForegroundColor Yellow
Write-Host " [Running Final Verification API]" -ForegroundColor Yellow
Write-Host "------------------------------------------------------" -ForegroundColor Yellow

$verifyRes = curl.exe -s -X POST "http://localhost:8080/api/admin/verification/run?policyId=1&force=true"
Write-Host "Verification Batch Dispatched: " $verifyRes -ForegroundColor Cyan
Start-Sleep -Seconds 3
$reportRes = curl.exe -s "http://localhost:8080/api/admin/verification/reports?policyId=1"
Write-Host "Verification Report: " $reportRes -ForegroundColor Green
