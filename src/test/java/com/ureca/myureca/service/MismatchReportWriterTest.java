package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MismatchReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void 불일치_내역을_CSV로_정확히_기록한다() throws IOException {
        VerificationAsyncTrigger.MismatchReportWriter writer =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        LocalDateTime runAt = LocalDateTime.of(2026, 8, 21, 12, 0, 0);

        Path csv = writer.write(1L, runAt, Set.of(100L, 300L), Set.of(100L, 999L), 0);

        assertThat(csv).exists();
        String content = Files.readString(csv);
        assertThat(content).startsWith("policyId,userId,discrepancyType,detectedAt\n");
        assertThat(content).contains("1,999,REDIS_ONLY," + runAt);
        assertThat(content).contains("1,300,DB_ONLY," + runAt);
        // 완전히 일치하는 100은 어느 쪽에도 없어야 한다
        assertThat(content).doesNotContain(",100,");
        assertThat(content).doesNotContain("OVERSOLD");
    }

    @Test
    void 초과발급이_있으면_OVERSOLD_행을_기록한다() throws IOException {
        VerificationAsyncTrigger.MismatchReportWriter writer =
                new VerificationAsyncTrigger.MismatchReportWriter(tempDir.toString());
        LocalDateTime runAt = LocalDateTime.of(2026, 8, 21, 12, 0, 0);

        // diff는 완전히 일치(불일치 유저 없음)해도 초과발급 50건이 있는 경우
        Path csv = writer.write(1L, runAt, Set.of(100L), Set.of(100L), 50);

        String content = Files.readString(csv);
        assertThat(content).contains("1,,OVERSOLD(+50)," + runAt);
    }
}
