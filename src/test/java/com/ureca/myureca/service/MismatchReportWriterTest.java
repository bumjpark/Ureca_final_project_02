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
                Set.of(100L, 300L), Set.of(100L, 999L), 0, 0, List.of(), Set.of(100L, 300L), Set.of());

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
                Set.of(100L), Set.of(100L), 50, 0, List.of(), Set.of(100L), Set.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,,,OVERSOLD(+50)," + RUN_AT);
    }

    @Test
    void 재고_누수가_있으면_STOCK_LEAK_행을_기록한다() throws IOException {
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L), Set.of(100L), 0, 3, List.of(), Set.of(100L), Set.of());

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
                Set.of(100L, 200L), Set.of(100L, 200L), 0, 0, anomalies, Set.of(100L, 200L), Set.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,100,10,HISTORY_MISMATCH," + RUN_AT);
        assertThat(content).contains("1,200,20,MISSING_HISTORY," + RUN_AT);
    }

    @Test
    void 선착순_불일치가_있으면_EXPECTED_NOT_ISSUED와_ISSUED_NOT_EXPECTED_행을_기록한다() throws IOException {
        // 도착순 상위 N명(expectedTopN)={100,300}인데 실제 DB 발급자(dbUserIds)={100,999}
        // -> 300은 먼저 왔는데 못 받음(EXPECTED_NOT_ISSUED), 999는 순번 밖인데 받음(ISSUED_NOT_EXPECTED)
        MismatchFindings findings = new MismatchFindings(
                Set.of(100L, 999L), Set.of(100L, 999L), 0, 0, List.of(), Set.of(100L, 300L), Set.of());

        Path csv = writer().write(1L, RUN_AT, findings);

        String content = Files.readString(csv);
        assertThat(content).contains("1,300,,EXPECTED_NOT_ISSUED," + RUN_AT);
        assertThat(content).contains("1,999,,ISSUED_NOT_EXPECTED," + RUN_AT);
        // 도착순대로 정상 발급된 100은 어느 쪽에도 없어야 한다
        assertThat(content).doesNotContain("100,,EXPECTED_NOT_ISSUED");
        assertThat(content).doesNotContain("100,,ISSUED_NOT_EXPECTED");
    }

    @Test
    void reportDir_안의_경로는_그대로_해석한다() throws IOException {
        Path csv = Files.writeString(tempDir.resolve("verification-1-123.csv"), "x");

        Path resolved = writer().resolveExistingFile(csv.toString());

        assertThat(resolved).isEqualTo(csv.toAbsolutePath().normalize());
    }

    @Test
    void reportDir_밖의_경로는_거부한다() {
        Path outside = tempDir.resolveSibling("outside-report.csv");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer().resolveExistingFile(outside.toString()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 경로_형식_자체가_깨진_reportUrl도_IllegalStateException으로_통일해서_던진다() {
        String malformed = tempDir.toString() + java.io.File.separator + "verification-1-1<>.csv";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer().resolveExistingFile(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.nio.file.InvalidPathException.class);
    }
}
