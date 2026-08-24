package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ureca.myureca.service.VerificationAsyncTrigger.LifecycleAnomaly;
import com.ureca.myureca.service.VerificationAsyncTrigger.MismatchFindings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MismatchReportWriterTest {

    @TempDir
    Path tempDir;

    private static final LocalDateTime RUN_AT = LocalDateTime.of(2026, 8, 21, 12, 0, 0);

    private VerificationAsyncTrigger.MismatchReportWriter writer() {
        return new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
    }

    @Test
    void 불일치_내역을_CSV로_정확히_기록한다() throws IOException {
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L, 300L), Set.of(100L, 999L), 0, 0, List.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        assertThat(csv).exists();
        String content = Files.readString(csv);
        assertThat(content).startsWith("policyId,userId,couponIssueId,discrepancyType,detectedAt\n");
        assertThat(content).contains("1,999,,REDIS_ONLY," + RUN_AT);
        assertThat(content).contains("1,300,,DB_ONLY," + RUN_AT);
        // 완전히 일치하는 100은 어느 쪽에도 없어야 한다
        assertThat(content).doesNotContain(",100,");
        assertThat(content).doesNotContain("OVERSOLD");
        assertThat(content).doesNotContain("STOCK_LEAK");
    }

    @Test
    void 초과발급이_있으면_OVERSOLD_행을_기록한다() throws IOException {
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L), Set.of(100L), 50, 0, List.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,,,OVERSOLD(+50)," + RUN_AT);
    }

    @Test
    void 재고_누수가_있으면_STOCK_LEAK_행을_기록한다() throws IOException {
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L), Set.of(100L), 0, 3, List.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,,,STOCK_LEAK(+3)," + RUN_AT);
    }

    @Test
    void 생명주기_불일치가_있으면_userId와_couponIssueId를_모두_채워서_기록한다() throws IOException {
        List<LifecycleAnomaly> anomalies = List.of(
                new LifecycleAnomaly(10L, 100L, "HISTORY_MISMATCH"),
                new LifecycleAnomaly(20L, 200L, "MISSING_HISTORY")
        );
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L, 200L), Set.of(100L, 200L), 0, 0, anomalies);

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,100,10,HISTORY_MISMATCH," + RUN_AT);
        assertThat(content).contains("1,200,20,MISSING_HISTORY," + RUN_AT);
    }
}
