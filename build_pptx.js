const pptxgen = require('pptxgenjs');
const path = require('path');

async function createPresentation() {
    const pptx = new pptxgen();
    
    // Define exact 16:9 Widescreen (13.333" x 7.5") to match coordinate system
    pptx.defineLayout({ name: 'WIDESCREEN_16_9', width: 13.333, height: 7.5 });
    pptx.layout = 'WIDESCREEN_16_9';

    // Color Palette: Premium Dark Tech Theme
    const BG_DARK = '0F172A'; // Slate 900
    const BG_CARD = '1E293B'; // Slate 800
    const TEXT_MAIN = 'F8FAFC'; // Slate 50
    const TEXT_MUTED = '94A3B8'; // Slate 400
    const ACCENT_BLUE = '38BDF8'; // Sky 400
    const ACCENT_GREEN = '34D399'; // Emerald 400
    const ACCENT_GOLD = 'FBBF24'; // Amber 400
    const ACCENT_RED = 'F87171'; // Red 400

    function addHeader(slide, title, subtitle, category = '선착순 쿠폰 발급 시스템') {
        slide.background = { color: BG_DARK };
        
        // Category Badge
        slide.addText(category.toUpperCase(), {
            x: 0.8, y: 0.4, w: 10, h: 0.3,
            fontSize: 10, fontFace: 'Arial', color: ACCENT_BLUE, bold: true
        });

        // Main Title
        slide.addText(title, {
            x: 0.8, y: 0.7, w: 11.5, h: 0.6,
            fontSize: 22, fontFace: 'Arial', color: TEXT_MAIN, bold: true
        });

        // Subtitle
        if (subtitle) {
            slide.addText(subtitle, {
                x: 0.8, y: 1.3, w: 11.5, h: 0.4,
                fontSize: 13, fontFace: 'Arial', color: TEXT_MUTED
            });
        }
    }

    function addFooter(slide, current, total = 22) {
        slide.addText(`LG U+ Ureca 2조 투게더  |  Slide ${current} / ${total}`, {
            x: 0.8, y: 7.0, w: 11.5, h: 0.3,
            fontSize: 9, fontFace: 'Arial', color: '64748B', align: 'right'
        });
    }

    // ==========================================
    // Slide 1: Cover
    // ==========================================
    {
        const slide = pptx.addSlide();
        slide.background = { color: BG_DARK };

        slide.addText('대규모 트래픽 선착순 쿠폰 발급 시스템', {
            x: 1.0, y: 1.5, w: 11.0, h: 0.4,
            fontSize: 14, fontFace: 'Arial', color: ACCENT_BLUE, bold: true, tracking: 2
        });

        slide.addText("20,000건의 동시 폭주 속에서\n'0'의 오차를 증명하다", {
            x: 1.0, y: 2.1, w: 11.0, h: 1.6,
            fontSize: 34, fontFace: 'Arial', color: TEXT_MAIN, bold: true, lineSpacing: 42
        });

        slide.addText('300만 건 데이터 정합성 보장과 5계층 장애 자가 치유 아키텍처', {
            x: 1.0, y: 3.9, w: 11.0, h: 0.5,
            fontSize: 16, fontFace: 'Arial', color: TEXT_MUTED
        });

        // 4 Key Metric Cards
        const metrics = [
            { label: '동시 가상 유저 (VU)', val: '20,000 VU', sub: '초과 발급 0건 완벽 방어', color: ACCENT_GREEN },
            { label: '최대 지연시간 개선', val: '43.88 ms', sub: '6.24초 ➜ 140배 단축', color: ACCENT_BLUE },
            { label: 'DB 처리량 최적화', val: '1,250 TPS', sub: 'JDBC Batch 5.5배 향상', color: ACCENT_GOLD },
            { label: '300만 건 정합성 검증', val: '6.2 초', sub: '전수 스캔 및 CSV 자동화', color: 'A78BFA' }
        ];

        metrics.forEach((m, idx) => {
            const xPos = 1.0 + idx * 2.85;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: xPos, y: 4.8, w: 2.65, h: 1.6,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(m.label, { x: xPos + 0.15, y: 4.95, w: 2.35, h: 0.3, fontSize: 10, color: TEXT_MUTED, fontFace: 'Arial' });
            slide.addText(m.val, { x: xPos + 0.15, y: 5.3, w: 2.35, h: 0.5, fontSize: 18, color: m.color, bold: true, fontFace: 'Arial' });
            slide.addText(m.sub, { x: xPos + 0.15, y: 5.85, w: 2.35, h: 0.3, fontSize: 9, color: TEXT_MAIN, fontFace: 'Arial' });
        });

        slide.addText('팀 2조 투게더: 박종범(팀장), 이용재, 이헌진, 정문구, 박찬영  |  LG유플러스 유레카 백엔드 종합프로젝트', {
            x: 1.0, y: 6.8, w: 11.0, h: 0.4, fontSize: 11, color: '64748B', fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 2: Problem Definition
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '선착순 이벤트의 3대 핵심 과제', '대규모 트래픽 환경에서 마주한 기술적 난제와 비즈니스 요구사항');
        addFooter(slide, 2);

        const cards = [
            {
                title: '1. 동시성 제어 (Race Condition)',
                desc: '10,000장 재고에 20,000건 동시 인입\n- 두 요청이 동시에 재고를 읽고 차감하는 경합\n- 재고 초과 발급(Over-selling) 0건 절대 보장\n- NFR-1 최우선 정합성 지표 달성',
                color: ACCENT_RED
            },
            {
                title: '2. 엄격한 1인 1매 제한',
                desc: '광클, 새로고침(F5) 연타, 멀티 탭, 봇 차단\n- 동일 유저의 동시 다중 요청 무효화\n- 1명의 유저가 여러 장을 획득하는 어뷰징 방지\n- 식별자 기반 원자적 중복 판정 필요',
                color: ACCENT_GOLD
            },
            {
                title: '3. RDB 커넥션 병목 격리',
                desc: '순간 폭주 트래픽의 WAS/DB 전파 차단\n- HikariCP(커넥션 풀 10~20개) 조기 고갈 방지\n- 스레드 블로킹으로 인한 서버 다운 방지\n- 발급 응답(동기)과 DB 쓰기(비동기)의 분리',
                color: ACCENT_BLUE
            }
        ];

        cards.forEach((c, idx) => {
            const xPos = 0.8 + idx * 3.85;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: xPos, y: 1.9, w: 3.65, h: 4.8,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(c.title, { x: xPos + 0.2, y: 2.1, w: 3.25, h: 0.5, fontSize: 13, color: c.color, bold: true, fontFace: 'Arial' });
            slide.addText(c.desc, { x: xPos + 0.2, y: 2.7, w: 3.25, h: 3.8, fontSize: 11, color: TEXT_MAIN, lineSpacing: 22, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 3: Industry Research & Engineering Question
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '현업 시스템 조사와 의문: "왜 Redis + Kafka인가?"', '남들이 쓰는 기술을 맹목적으로 따르지 않고 실측으로 증명하기로 한 이유');
        addFooter(slide, 3);

        const sections = [
            {
                title: '1. 현업 서비스 조사 (배민, 무신사, 인터파크 등)',
                desc: '• 대규모 트래픽 기업들의 공통 아키텍처: "Redis (인메모리) + Kafka (메시지 큐)"\n• 순간 폭주 트래픽을 인메모리에서 걸러내고, DB 저장은 비동기 큐로 완충하는 표준 패턴 확인',
                color: ACCENT_BLUE
            },
            {
                title: '2. 팀의 엔지니어링 의문 (Why?)',
                desc: '• "남들이 쓴다고 무작정 도입하는 것은 엔지니어링이 아니다."\n• 왜 Java 락이나 DB 락으로는 안 될까? 단순 Redis 명령어로는 왜 동시성이 터질까?\n• Kafka만 도입하면 정말 무조건 빨라지는가? 실제 병목은 어디에 있는가?',
                color: ACCENT_GOLD
            },
            {
                title: '3. 가설과 실측 기반의 8단계 검증 여정',
                desc: '• 가장 원초적인 순수 Java(락 없음)부터 DB 비관적 락, Redis Lua, Kafka Consumer 최적화까지\n• 각 대안에 20,000건 부하를 직접 걸어 한계를 깨뜨려보고, 수치로 증명하며 최적의 아키텍처를 도출하기로 결정',
                color: ACCENT_GREEN
            }
        ];

        sections.forEach((s, idx) => {
            const yPos = 1.9 + idx * 1.65;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.45,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(s.title, { x: 1.1, y: yPos + 0.15, w: 10.9, h: 0.35, fontSize: 12.5, color: s.color, bold: true, fontFace: 'Arial' });
            slide.addText(s.desc, { x: 1.1, y: yPos + 0.55, w: 10.9, h: 0.8, fontSize: 10.5, color: TEXT_MAIN, lineSpacing: 18, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 4: 1~3 Test Failure Analysis
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '1~3차 테스트: 애플리케이션 제어 & DB 락의 한계', '실제 부하 테스트를 통한 대안 탈락 과정과 실패 분석');
        addFooter(slide, 4);

        const rows = [
            ['방식', '부하 조건', '정합성 결과', '핵심 실패 원인', '판정'],
            ['순수 Java (락 없음)', '20,000건', '10,054건 발급\n(오버셀 54건)', '동시성 경합으로 Check-then-Act 원자성 파괴', '탈락 (FAIL)'],
            ['AtomicInteger', '20,000건', '10,000건\n(카운트 정확)', 'JVM 메모리 종속, 서버 재시작 및 다중화 시 리셋', '탈락 (FAIL)'],
            ['RDB 비관적 락\n(SELECT FOR UPDATE)', '20,000건', '10,000건\n(카운트 정확)', 'HikariCP 커넥션 풀 완전 고갈로 타임아웃 및 서버 마비', '탈락 (FAIL)']
        ];

        slide.addTable(rows, {
            x: 0.8, y: 1.9, w: 11.5, h: 3.5,
            fill: { color: BG_CARD },
            color: TEXT_MAIN,
            fontSize: 10.5,
            fontFace: 'Arial',
            align: 'center',
            valign: 'middle',
            border: { color: '334155', width: 1 }
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 5.7, w: 11.5, h: 1.0,
            fill: { color: '1E1B4B' }, line: { color: '4338CA', width: 1 }
        });
        slide.addText('💡 핵심 엔지니어링 결론: 대규모 동시성 환경에서는 RDB 트랜잭션 락을 배제한 인메모리 원자 제어 솔루션이 필수적임.', {
            x: 1.0, y: 5.85, w: 11.1, h: 0.7, fontSize: 11.5, color: ACCENT_BLUE, bold: true, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 5: 4~5 Test Redis Lua
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '4~5차 테스트: Redis 도입과 Lua Script의 원자성', 'Check-then-Act 문제 해결과 분산 락 없는 고성능 달성');
        addFooter(slide, 5);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 5.6, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('❌ 단순 Redis DECR의 실패 (4차)', { x: 1.0, y: 2.1, w: 5.2, h: 0.4, fontSize: 13, color: ACCENT_RED, bold: true, fontFace: 'Arial' });
        slide.addText('• GET(재고조회) ➜ SISMEMBER(중복체크) ➜ DECR(차감) 분리\n• 명령 사이의 시간 차(Check-then-Act)로 동시성 침투\n• 실측 결과: 60,000건 요청 시 6,646건 중복 발급 발생\n• 결론: 개별 명령어 호출만으로는 원자성 보장 불가', {
            x: 1.0, y: 2.6, w: 5.2, h: 3.8, fontSize: 11, color: TEXT_MAIN, lineSpacing: 22, fontFace: 'Arial'
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 6.7, y: 1.9, w: 5.6, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('✅ Redis Lua Script (EVAL) 적용 (5차)', { x: 6.9, y: 2.1, w: 5.2, h: 0.4, fontSize: 13, color: ACCENT_GREEN, bold: true, fontFace: 'Arial' });
        slide.addText('• SCARD + SISMEMBER + SADD를 단일 원자 스크립트로 묶음\n• Redis 싱글스레드에서 단 1회의 트랜잭션으로 원자 실행\n• 별도 분산 락(Redisson) 대기 시간(RTT) 0초 달성\n• 실측 결과: 60,000건 동시 요청 시 초과발급 0건, 877ms 마감', {
            x: 6.9, y: 2.6, w: 5.2, h: 3.8, fontSize: 11, color: TEXT_MAIN, lineSpacing: 22, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 6: 6th Test Synchronous DB Bottleneck
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '6차 테스트: Redis + 동기 DB 저장의 병목', 'Redis는 1ms인데 왜 전체 처리는 12.7초가 걸리는가?');
        addFooter(slide, 6);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 11.5, h: 1.5,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('실측 데이터 비교 (Redis 판정 vs 동기 DB 저장)', { x: 1.1, y: 2.05, w: 10.9, h: 0.3, fontSize: 12, color: ACCENT_GOLD, bold: true, fontFace: 'Arial' });
        slide.addText('• Redis Lua 판정 시간 : 1 ms 미만 (초고속 승인)\n• 전체 소요 시간 : 12,706 ms (12.7초, 심각한 지연)\n• 가장 오래 걸린 DB 저장 대기 시간 : 7,413.41 ms', {
            x: 1.1, y: 2.4, w: 10.9, h: 0.9, fontSize: 11, color: TEXT_MAIN, lineSpacing: 20, fontFace: 'Arial'
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 3.6, w: 11.5, h: 3.1,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('병목 원인 분석 & 엔지니어링 통찰', { x: 1.1, y: 3.8, w: 10.9, h: 0.3, fontSize: 12, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
        slide.addText('1. DB 커넥션 풀(HikariCP 10~20개)이 순간적으로 몰리는 2만 건의 동시 쓰기 요청을 감당하지 못함.\n2. 서버 스레드가 DB INSERT 완료를 기다리느라 블로킹되어 뒤쪽 요청들이 도미노처럼 큐잉 지연.\n3. 핵심 결론: "발급 승인 응답(동기)"과 "DB 영속화(비동기)"는 반드시 아키텍처적으로 분리되어야 함.', {
            x: 1.1, y: 4.2, w: 10.9, h: 2.3, fontSize: 11, color: TEXT_MAIN, lineSpacing: 24, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 7: Kafka Pitfall
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, 'Kafka 도입의 함정: 단순 도입 vs 배치 최적화', 'Kafka만 얹는다고 빨라지지 않는다 — Consumer 단건 저장의 참사');
        addFooter(slide, 7);

        const rows = [
            ['구분', 'Redis Only (동기 DB)', 'Redis + Kafka (단건 저장)', '결과 방향'],
            ['처리 시간', '6,700 ms (2,985건/s)', '10,858 ms (1,816건/s)', '❌ 1.6배 성능 악화'],
            ['오류 건수', '0건', '283건 오류 발생', '❌ 컨슈머 지연으로 실패 발생'],
            ['원인 분석', 'DB 커넥션 병목', '메시지 1건마다 @Transactional + 단건 INSERT\n(건별 DB 왕복 및 fsync 병목)', '배치 없는 Kafka는 오버헤드만 가중']
        ];

        slide.addTable(rows, {
            x: 0.8, y: 1.9, w: 11.5, h: 3.4,
            fill: { color: BG_CARD },
            color: TEXT_MAIN,
            fontSize: 10.5,
            fontFace: 'Arial',
            align: 'center',
            valign: 'middle',
            border: { color: '334155', width: 1 }
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 5.6, w: 11.5, h: 1.1,
            fill: { color: '1E1B4B' }, line: { color: '4338CA', width: 1 }
        });
        slide.addText('💡 핵심 인사이트: Kafka 자체는 가속기가 아니다. Kafka의 진짜 존재 가치는 "DB 쓰기 작업을 일괄(Batch) 처리할 수 있도록 완충해 주는 것"에 있다.', {
            x: 1.0, y: 5.75, w: 11.1, h: 0.8, fontSize: 11.5, color: ACCENT_BLUE, bold: true, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 8: Consumer Optimization
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, 'Kafka Consumer 최적화: SEQUENCE 채번 & JDBC Batch', 'DB 쓰기 처리량 5.5배 폭발적 개선 (229 TPS ➜ 1,250 TPS)');
        addFooter(slide, 8);

        const optims = [
            {
                title: '1. IDENTITY ➜ SEQUENCE 채번 전환',
                desc: 'MySQL IDENTITY는 ID 조회를 위해 매 save마다 강제 flush 발생 ➜ SEQUENCE 메모리 사전할당(allocationSize=50)으로 트랜잭션 종료 시 일괄 flush 달성'
            },
            {
                title: '2. JDBC Batch Insert 활성화',
                desc: 'rewriteBatchedStatements=true 설정으로 수백 개 개별 INSERT 쿼리를 단 1개의 Multi-Row Bulk INSERT 문으로 압축 전송'
            },
            {
                title: '3. 100건 청크 트랜잭션 & Fallback',
                desc: '정상 건은 100건 청크 단위 일괄 처리(processChunk), UNIQUE 제약 위반 발생 청크만 건별(processSingle)로 안전하게 폴백 격리'
            }
        ];

        optims.forEach((o, idx) => {
            const yPos = 1.9 + idx * 1.25;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.1,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(o.title, { x: 1.0, y: yPos + 0.15, w: 11.1, h: 0.3, fontSize: 12, color: ACCENT_GOLD, bold: true, fontFace: 'Arial' });
            slide.addText(o.desc, { x: 1.0, y: yPos + 0.45, w: 11.1, h: 0.55, fontSize: 10.5, color: TEXT_MAIN, fontFace: 'Arial' });
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 5.8, w: 11.5, h: 0.9,
            fill: { color: '064E3B' }, line: { color: '059669', width: 1 }
        });
        slide.addText('🚀 개선 결과: DB 영속화 시간 43.6초 ➜ 8.0초 (DB 처리량 229 TPS ➜ 1,250 TPS, 5.5배 향상 / 드롭 0건)', {
            x: 1.0, y: 6.0, w: 11.1, h: 0.5, fontSize: 12, color: ACCENT_GREEN, bold: true, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 9: 2-Set
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '2-Set 구조(RESERVED ➜ ISSUED)와 초저지연 응답', '최대 지연 시간 6.24초 ➜ 43.88ms (140배 단축)');
        addFooter(slide, 9);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 11.5, h: 3.4,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('2단계 상태 머신 & 자가 치유 라이프사이클', { x: 1.1, y: 2.1, w: 10.9, h: 0.35, fontSize: 13, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
        slide.addText('1단계 (임시 예약): Lua Script 차감 후 RESERVED ZSET에 {userId: timestamp} 등록 ➜ 즉시 202 ACCEPTED 응답 (1ms, Fire-and-Forget)\n2단계 (DB 확정): Kafka Consumer가 DB INSERT 완료 후 RESERVED에서 제거하고 ISSUED SET으로 확정 승격\n3단계 (유실 복구 안전망): ReconciliationScheduler가 1분마다 60초 이상 RESERVED에 남은 "미아 예약"을 감지해 DB 확인 후 자동 재발행', {
            x: 1.1, y: 2.55, w: 10.9, h: 2.5, fontSize: 11, color: TEXT_MAIN, lineSpacing: 24, fontFace: 'Arial'
        });

        const resCards = [
            { label: '발급 성공 평균 지연', val: '1.09 ms', sub: '5.16ms에서 대폭 단축' },
            { label: 'P95 응답 지연', val: '1.77 ms', sub: '6.66ms에서 안정화' },
            { label: '최대 지연 시간 (Max)', val: '43.88 ms', sub: '6.24초 대비 140배 개선' }
        ];
        resCards.forEach((rc, idx) => {
            const xPos = 0.8 + idx * 3.85;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: xPos, y: 5.5, w: 3.65, h: 1.2,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(rc.label, { x: xPos + 0.15, y: 5.6, w: 3.35, h: 0.25, fontSize: 10, color: TEXT_MUTED, fontFace: 'Arial' });
            slide.addText(rc.val, { x: xPos + 0.15, y: 5.85, w: 3.35, h: 0.4, fontSize: 16, color: ACCENT_GREEN, bold: true, fontFace: 'Arial' });
            slide.addText(rc.sub, { x: xPos + 0.15, y: 6.25, w: 3.35, h: 0.25, fontSize: 9, color: TEXT_MAIN, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 10: Evolution Summary
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '아키텍처 진화 단계별 벤치마크 총괄 비교', '수치로 입증하는 6단계 최적화 과정과 최종 성과');
        addFooter(slide, 10);

        const rows = [
            ['단계', '아키텍처 방식', '처리 속도 / TPS', '정합성 (초과발급)', '핵심 평가'],
            ['1단계', '순수 Java (락 없음)', '41 ms', '❌ 54건 초과 발급', '레이스 컨디션 발생'],
            ['2단계', 'AtomicInteger', '42 ms', '⚠️ 0건 (단일서버)', '서버 재시작 시 데이터 소멸'],
            ['3단계', 'RDB 비관적 락', '타임아웃 발생', '⚠️ 0건', '커넥션 풀 완전 고갈'],
            ['4단계', 'Redis 단순 DECR', '3,612 ms', '❌ 6,646건 중복 발급', 'Check-then-Act 원자성 파괴'],
            ['5단계', 'Redis Lua + 동기 DB', '12,706 ms', '✅ 0건', 'DB 커넥션 병목 심각'],
            ['6단계', 'Redis Lua + Kafka (단건)', '10,858 ms', '⚠️ 283건 오류 발생', 'Consumer 단건 저장 지연'],
            ['최종', 'Redis Lua + Kafka Batch + 2-Set', '6.0초 (3,358 TPS)\nMax 43.88ms', '✅ 0건 (완벽)', '초과발급 0건, P50 46ms, 무결성 100%']
        ];

        slide.addTable(rows, {
            x: 0.8, y: 1.8, w: 11.5, h: 5.0,
            fill: { color: BG_CARD },
            color: TEXT_MAIN,
            fontSize: 9.5,
            fontFace: 'Arial',
            align: 'center',
            valign: 'middle',
            border: { color: '334155', width: 1 }
        });
    }

    // ==========================================
    // Slide 11: Network Drop
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '트래픽 폭주와 네트워크 유실: 대기열 도입의 계기', '로컬 OS 소켓 한계와 Thundering Herd 극복');
        addFooter(slide, 11);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 11.5, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('대규모 부하 테스트 중 마주한 네트워크 레벨의 병목', { x: 1.1, y: 2.1, w: 10.9, h: 0.35, fontSize: 13, color: ACCENT_RED, bold: true, fontFace: 'Arial' });
        slide.addText('1. 소켓 연결 거부 (Connection Refused 29.77% 발생)\n   - 2만~10만 가상 유저가 0초에 동시 인입되면서 OS의 TCP 소켓 포트(65,535개) 고갈 (TIME_WAIT 상태)\n   - 서버 비즈니스 로직은 멀쩡하지만 네트워크 진입로에서 패킷이 드롭되는 현상 발생\n\n2. 사용자 새로고침(F5) 연타 및 매크로 봇의 Thundering Herd\n   - 진입 대기 중인 유저들이 불안감으로 F5를 연타하여 수만 건의 중복 요청이 쏟아짐\n\n3. 아키텍처 설계 원칙의 확장:\n   "정합성은 Redis Lua Script가 보장하지만, 시스템 입구를 보호하고 유량을 조절하는 유량 제어(Traffic Control)는 맨 앞단의 대기열이 담당해야 한다."', {
            x: 1.1, y: 2.6, w: 10.9, h: 3.8, fontSize: 11, color: TEXT_MAIN, lineSpacing: 22, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 12: Queue
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '동적 스케일링 대기열 & SSE 실시간 통보 아키텍처', 'ZPOPMIN 원자적 추출 + 파이프라이닝 토큰 발급 + Server-Sent Events 푸시');
        addFooter(slide, 12);

        const queueSteps = [
            { step: '1. 대기열 등록 (/queue/join) & 멱등성', desc: 'Redis ZSET(Score=timestamp)에 순번 등록. F5 연타 시에도 ZSCORE로 기존 순번을 멱등하게 유지하여 중복 줄서기 차단' },
            { step: '2. 분산 락 기반 입장 스케줄러 & 자동 스케일링', desc: '1초 주기 QueueAdmissionScheduler가 분산 락(SETNX) 획득 후, 대기열 부하에 따라 QueueLimitAdminService로 입장 정원을 자동 스케일링' },
            { step: '3. 원자적 추출 & 파이프라이닝 토큰 발급 (ZPOPMIN)', desc: 'Math.min(batchSize, stock) 정밀 슬라이싱 후 ZPOPMIN으로 선두 인원을 원자적 추출하고, Redis Pipelining으로 60초 TTL activeToken 일괄 등록' },
            { step: '4. SSE(Server-Sent Events) 실시간 토큰 푸시 & 보안 검증', desc: '클라이언트 폴링 없이 SSE 스트림으로 activeToken 실시간 전달 ➜ /issue 호출 시 토큰 소유자(userId) 일치 여부를 원자적으로 소비/검증하여 매크로 차단' }
        ];

        queueSteps.forEach((qs, idx) => {
            const yPos = 1.9 + idx * 1.25;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.1,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(qs.step, { x: 1.0, y: yPos + 0.15, w: 11.1, h: 0.3, fontSize: 12, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
            slide.addText(qs.desc, { x: 1.0, y: yPos + 0.45, w: 11.1, h: 0.55, fontSize: 10.5, color: TEXT_MAIN, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 13: 5 Inversion Defense
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '선착순 공정성 & 5대 역전 시나리오 완벽 방어', '배치 내 미세 역전 허용 vs 경계선 새치기 0% 차단');
        addFooter(slide, 13);

        const rows = [
            ['상황 / 시나리오', '역전 발생 여부', '공정성 및 시스템 영향', '적용된 방어 기술 / 원리'],
            ['1. 대기열 줄서기', '❌ 역전 불가 (0%)', '먼저 도착한 패킷이 무조건 낮은 순번 수령 (100% 공정)', 'Redis 싱글스레드 + INCR 단조 증가'],
            ['2. 동일 배치 내 발급 (300명)', '⚠️ 미세 역전 허용', '300명 모두 당첨 확정 그룹이므로 공정성 피해 없음', 'activeToken 보유자 그룹 동시 처리'],
            ['3. 당첨/탈락 경계선 (마지막 10개)', '❌ 절대 불가 (새치기 차단)', '11등이 10등을 제치고 쿠폰 가로채기 0건 보장', 'Math.min(batch, stock) 정밀 슬라이싱'],
            ['4. 60초 타임아웃 만료', '⭕ 정상 추월 (정책적)', '잠수 유저 슬롯을 다음 대기자에게 자동 양도', 'activeToken TTL + EXPIRED 만료 마커'],
            ['5. 서버 다중화 (서버 A, B)', '❌ 역전 불가', '서버 N대 분산 환경에서도 단일 시퀀스 보장', '중앙 집중형 Redis ZSET 시퀀스']
        ];

        slide.addTable(rows, {
            x: 0.8, y: 1.8, w: 11.5, h: 5.0,
            fill: { color: BG_CARD },
            color: TEXT_MAIN,
            fontSize: 9.5,
            fontFace: 'Arial',
            align: 'center',
            valign: 'middle',
            border: { color: '334155', width: 1 }
        });
    }

    // ==========================================
    // Slide 14: 5-Layer Defense
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '시스템 장애 격리 & 5계층 방어 로직', '어느 레이어가 터져도 서비스는 중단되지 않는다');
        addFooter(slide, 14);

        const layers = [
            { l: '1계층: 유입 제어', c: 'API Gateway & Redis ZSET', d: '소켓 폭풍 방어, F5 중복 줄서기 차단, activeToken 우회 차단', color: ACCENT_BLUE },
            { l: '2계층: 메모리 검증', c: 'Redis Lua & 2-State', d: '0.5ms 만에 400 Fast-Fail, DB 트래픽 99% 차단, 1인 1매 엄격 보장', color: ACCENT_RED },
            { l: '3계층: 비동기 완충', c: 'Apache Kafka Broker', d: 'policyId+userId 파티션 키로 순서 보장, DB 지연 흡수, DLT 격리', color: ACCENT_GOLD },
            { l: '4계층: 자가 치유', c: 'ReconciliationScheduler', d: '60초 초과 유실 예약 건 1분 주기 감지 ➜ DB 확인 후 Kafka 자동 재발행', color: 'A78BFA' },
            { l: '5계층: 최종 영속화', c: 'MySQL (InnoDB & JPA)', d: '복합 유니크 제약(policy_id, user_id) ➜ 중복 메시지 100% 멱등 흡수', color: ACCENT_GREEN }
        ];

        layers.forEach((ly, idx) => {
            const yPos = 1.9 + idx * 0.98;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 0.85,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(ly.l, { x: 1.0, y: yPos + 0.12, w: 2.2, h: 0.3, fontSize: 11, color: ly.color, bold: true, fontFace: 'Arial' });
            slide.addText(ly.c, { x: 3.3, y: yPos + 0.12, w: 2.8, h: 0.3, fontSize: 10.5, color: TEXT_MAIN, bold: true, fontFace: 'Arial' });
            slide.addText(ly.d, { x: 6.2, y: yPos + 0.12, w: 5.9, h: 0.6, fontSize: 9.5, color: TEXT_MUTED, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 15: Redis Recovery
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, 'Redis 완전 유실 장애 복구 & Kafka Lag 안전 방어선', '부하 도중 FLUSHALL(전멸) 발생 시 SSOT(MySQL) 기반 원자적 복원');
        addFooter(slide, 15);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 11.5, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('극단적 카오스 테스트: 부하 도중 Redis FLUSHALL 강제 주입 (T = 32s)', { x: 1.1, y: 2.1, w: 10.9, h: 0.35, fontSize: 13, color: ACCENT_RED, bold: true, fontFace: 'Arial' });
        slide.addText('• 장애 상황: 4,557건 발급 완료, Kafka에 4,311건 처리 중, 잔여 재고 5,443건인 시점에 Redis 완전 삭제\n\n🛡️ 안전 복구 메커니즘 (RedisRecoveryService):\n1. Kafka Lag 안전 방어선: 컨슈머 랙(Lag > 0) 존재 시 섣부른 복구 시도를 409 Conflict로 즉시 차단 ➜ 중복 발급 원천 방어\n2. Lag = 0 도달 시 SSOT 복원: MySQL 실제 저장량(4,557건) 기준으로 잔여 재고(5,443개) 및 발급자 SET 116ms 만에 원자 재구성\n\n✅ 최종 데이터 정합성 검증 결과:\n• DB 발급(4,557) + Redis 잔여(5,443) = 정확히 10,000장 보존 (PASS)\n• 1인 1매 원칙 100% 준수 (중복 0건), 감사 로그 100% 일치, 정합성 배치 SUCCESS', {
            x: 1.1, y: 2.6, w: 10.9, h: 3.8, fontSize: 11, color: TEXT_MAIN, lineSpacing: 22, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 16: 3M Verification Architecture & Decision
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '300만 건 정합성 검증: 체크섬의 한계와 집합(Set) Diff 아키텍처', '단순 카운트 비교를 넘어선 대규모 데이터 무결성 검증 설계');
        addFooter(slide, 16);

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 1.9, w: 5.6, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('❌ XOR 체크섬 방식 검토 및 탈락 사유', { x: 1.0, y: 2.1, w: 5.2, h: 0.4, fontSize: 13, color: ACCENT_RED, bold: true, fontFace: 'Arial' });
        slide.addText('• 초기 검토: 빠른 집계를 위해 BIT_XOR(user_id) 고려\n\n1. 오류 상쇄 (Collision 위험):\n- 서로 다른 두 이상 건이 우연히 같은 체크섬을 만들어 정상으로 오판\n\n2. 중복 자기소멸 (A ⊕ A = 0):\n- 동일 유저가 중복 발급되면 XOR 특성상 0으로 소멸되어 중복 감지 불가!\n\n➜ 결론: 체크섬 대신 100% 신뢰 가능한 집합(Set) 대조 채택', {
            x: 1.0, y: 2.6, w: 5.2, h: 3.8, fontSize: 10.5, color: TEXT_MAIN, lineSpacing: 20, fontFace: 'Arial'
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 6.7, y: 1.9, w: 5.6, h: 4.8,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('✅ 최종 채택: Set 기반 양방향 Diff & 불변식 엔진', { x: 6.9, y: 2.1, w: 5.2, h: 0.4, fontSize: 13, color: ACCENT_GREEN, bold: true, fontFace: 'Arial' });
        slide.addText('• 커버링 인덱스 기반 DB 발급자 Set<Long> 추출\n• Redis 확정 발급자 Set<Long> 인메모리 로드\n• O(N) 양방향 대칭차집합(Diff) 연산:\n  - Redis_Only 및 DB_Only 유저 목록 정밀 색출\n• 300만 건 대용량 스캔 중에도 메모리 1.45GB 유지\n• 이상 발생 시 유저 추적용 CSV 리포트 자동 생성', {
            x: 6.9, y: 2.6, w: 5.2, h: 3.8, fontSize: 10.5, color: TEXT_MAIN, lineSpacing: 20, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 17: 5 Integrity Engines
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '5대 정합성 검증 엔진 & 자동 CSV 리포팅', '실무 수준의 불일치 원인 추적 시스템 (VerificationAsyncTrigger)');
        addFooter(slide, 17);

        const engines = [
            { n: '1. 오버셀 검증', d: 'DB 발급 수 > 정책 총 수량 초과 여부 검사 (NFR-1 절대 수호)' },
            { n: '2. Redis ↔ DB Diff 검증', d: 'Redis 확정 발급 유저 집합과 DB 발급 유저 집합의 양방향 일치 대조 (대칭차집합)' },
            { n: '3. 재고 누수 검증', d: 'Redis 잔여재고 + DB 발급수 + RESERVED 잔량 = 총 수량 삼각 불변식 검증' },
            { n: '4. 생애주기 정합성 검증', d: '발급(ISSUED) ➜ 사용(USED) ➜ 취소 간 CouponIssue와 History 상태 일치 검증' },
            { n: '5. FCFS 선착순 검증', d: '대기열 도착 상위 N명 집합(queue_join_log)과 실제 DB 발급자 집합 일치 검증' }
        ];

        engines.forEach((eg, idx) => {
            const yPos = 1.9 + idx * 0.85;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 0.75,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(eg.n, { x: 1.0, y: yPos + 0.15, w: 3.2, h: 0.3, fontSize: 11.5, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
            slide.addText(eg.d, { x: 4.3, y: yPos + 0.15, w: 7.8, h: 0.45, fontSize: 10.5, color: TEXT_MAIN, fontFace: 'Arial' });
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 6.2, w: 11.5, h: 0.65,
            fill: { color: '064E3B' }, line: { color: '059669', width: 1 }
        });
        slide.addText('📊 자동 리포팅: 불일치 발견 시 유저 ID, 발생 시각, 원인별 세부 내역을 담은 CSV 파일 자동 생성 및 다운로드 API 제공', {
            x: 1.0, y: 6.35, w: 11.1, h: 0.35, fontSize: 10.5, color: ACCENT_GREEN, bold: true, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 18: 3M Verification Benchmark
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '300만 건 검증 배치 실측 벤치마크', '300만 건 전수 검증 단 6.2초 완료 — OOM 없는 안정적 실행');
        addFooter(slide, 18);

        const rows = [
            ['정책 시나리오', '검증 판정', '감지된 mismatchCount', '소요 시간', '검증 상세'],
            ['clean-1m', '✅ SUCCESS', '0건', '6.3 초', '100만 건 완전 무결점 검증 통과'],
            ['mismatch-overissue', '🚨 MISMATCH_FOUND', '1,300건', '6.2 초', '사전 주입된 초과발급 데이터 정확히 적발'],
            ['lifecycle-anomaly', '🚨 MISMATCH_FOUND', '1,000건', '6.1 초', '사전 주입된 이력 누락 데이터 정확히 적발']
        ];

        slide.addTable(rows, {
            x: 0.8, y: 1.9, w: 11.5, h: 3.4,
            fill: { color: BG_CARD },
            color: TEXT_MAIN,
            fontSize: 10.5,
            fontFace: 'Arial',
            align: 'center',
            valign: 'middle',
            border: { color: '334155', width: 1 }
        });

        slide.addShape(pptx.shapes.RECTANGLE, {
            x: 0.8, y: 5.6, w: 11.5, h: 1.1,
            fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
        });
        slide.addText('자원 사용량 및 시스템 안정성:', { x: 1.0, y: 5.75, w: 11.1, h: 0.3, fontSize: 12, color: ACCENT_GOLD, bold: true, fontFace: 'Arial' });
        slide.addText('• 100만 유저 + 300만 발급이력 + 299.9만 감사로그 대상 비동기 인덱스 레인지 스캔\n• Spring Boot 힙 메모리 1.45GB로 안정 유지 (OOM 발생 0% 달성)', {
            x: 1.0, y: 6.1, w: 11.1, h: 0.5, fontSize: 10.5, color: TEXT_MAIN, fontFace: 'Arial'
        });
    }

    // ==========================================
    // Slide 19: Live Demo
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '라이브 시스템 시연 (5분)', '4단계 핵심 플로우 라이브 시연');
        addFooter(slide, 19);

        const demos = [
            { step: 'Step 1. 대기열 & 선착순 발급 (k6 20,000 VU)', desc: '모바일 뷰 대기 순번 카운트다운 ➜ 발급 성공(202) ➜ 실시간 재고 1만 개 정확 소진 확인' },
            { step: 'Step 2. 관리자 실시간 대시보드 모니터링', desc: '초당 유입량, Redis 잔여 재고 0, Kafka Consumer 배치 적재 현황 실시간 관측' },
            { step: 'Step 3. 카오스 엔지니어링 (Redis FLUSHALL & 복구)', desc: '부하 도중 Redis 삭제 ➜ Kafka Lag 감지 복구 차단 ➜ SSOT 원자 복원 시연' },
            { step: 'Step 4. 300만 건 정합성 검증 배치 실행', desc: '검증 수동 실행 ➜ 6.2초 만에 검증 완료 ➜ 정합성 리포트 및 CSV 다운로드' }
        ];

        demos.forEach((d, idx) => {
            const yPos = 1.9 + idx * 1.25;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.1,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(d.step, { x: 1.0, y: yPos + 0.15, w: 11.1, h: 0.3, fontSize: 12, color: ACCENT_GREEN, bold: true, fontFace: 'Arial' });
            slide.addText(d.desc, { x: 1.0, y: yPos + 0.45, w: 11.1, h: 0.55, fontSize: 10.5, color: TEXT_MAIN, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 20: Final Summary
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '최종 성과 요약 및 기여도', '이론을 실측으로 증명한 대규모 트래픽 분산 시스템');
        addFooter(slide, 20);

        const sumCards = [
            { t: '🎯 동시성 정합성', v: '초과 발급 0건', d: '20,000건 동시 요청 무결성 100%' },
            { t: '⚡ 초저지연 응답', v: 'Max 43.88 ms', d: '2-Set 구조로 6.24초 대비 140배 단축' },
            { t: '🚀 DB 쓰기 최적화', v: '1,250 TPS', d: 'SEQUENCE + JDBC Batch 5.5배 개선' },
            { t: '🛡️ 장애 자가 치유', v: '100% 무결점 복원', d: 'Lag 방어선 + SSOT 원자 복구' },
            { t: '🔍 대용량 신뢰성', v: '6.2초 완료', d: '300만 건 검증 및 CSV 자동 리포팅' }
        ];

        sumCards.forEach((sc, idx) => {
            const xPos = idx < 3 ? 0.8 + idx * 3.85 : 2.7 + (idx - 3) * 3.85;
            const yPos = idx < 3 ? 1.9 : 4.4;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: xPos, y: yPos, w: 3.65, h: 2.2,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(sc.t, { x: xPos + 0.15, y: yPos + 0.2, w: 3.35, h: 0.35, fontSize: 12, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
            slide.addText(sc.v, { x: xPos + 0.15, y: yPos + 0.65, w: 3.35, h: 0.6, fontSize: 18, color: ACCENT_GREEN, bold: true, fontFace: 'Arial' });
            slide.addText(sc.d, { x: xPos + 0.15, y: yPos + 1.35, w: 3.35, h: 0.65, fontSize: 10, color: TEXT_MAIN, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 21: Engineering Insights
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, '엔지니어링 인사이트 & 배운 점', '"수치보다 중요한 것은 문제를 파헤치는 엔지니어링 태도"');
        addFooter(slide, 21);

        const insights = [
            {
                title: '1. 가설과 실측의 반복',
                desc: '"Kafka를 쓰면 무조건 빨라질 것이다"라는 환상을 버리고, 실측을 통해 Consumer 단건 저장 병목을 찾아내어 Batch로 개선함.'
            },
            {
                title: '2. 정합성과 유량 제어의 명확한 분리',
                desc: '"무엇이 맞는 답인가(Lua 원자성)"와 "얼마나 흘려보낼 것인가(대기열 동시성 제한)"의 책임을 각 계층에 명확히 분리함.'
            },
            {
                title: '3. 장애를 전제한 분산 아키텍처 설계',
                desc: '네트워크 순단과 캐시 유실은 언제든 발생할 수 있으므로, SSOT(단일 진실 공급원)와 자가 치유 스케줄러를 통한 다중 안전망의 필수성을 체득함.'
            }
        ];

        insights.forEach((ins, idx) => {
            const yPos = 1.9 + idx * 1.6;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.4,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(ins.title, { x: 1.1, y: yPos + 0.15, w: 10.9, h: 0.35, fontSize: 13, color: ACCENT_GOLD, bold: true, fontFace: 'Arial' });
            slide.addText(ins.desc, { x: 1.1, y: yPos + 0.55, w: 10.9, h: 0.7, fontSize: 11, color: TEXT_MAIN, lineSpacing: 20, fontFace: 'Arial' });
        });
    }

    // ==========================================
    // Slide 22: Q & A
    // ==========================================
    {
        const slide = pptx.addSlide();
        addHeader(slide, 'Q & A', '경청해 주셔서 감사합니다. 질문에 성심성의껏 답변드리겠습니다.');
        addFooter(slide, 22);

        const qnas = [
            { q: 'Q1. Redis Lua가 싱글스레드인데 트래픽이 10배 늘어나면 병목이 되지 않나요?', a: 'Lua 연산을 O(1)로 단순화하여 1회 0.1ms 미만입니다. 추가 확장 시 재고 키 샤딩(stock_1, stock_2)과 대기열 동시성 제한으로 CPU 포화를 방어합니다.' },
            { q: 'Q2. Redis 발급 후 DB 저장이 영구 실패하면 어떻게 되나요?', a: '발급 즉시 RESERVED로 등록되며, DLT 격리와 함께 1분 주기 스케줄러가 60초 초과 미아 건을 DB 확인 후 자동 재발행합니다.' },
            { q: 'Q3. 300만 건 검증이 운영 DB에 부하를 주지 않나요?', a: '실무 환경은 Read Replica나 CDC 기반 DW에서 검증하며, 본 시스템은 커버링 인덱스와 청크 단위 비동기 배치로 락 경합을 최소화했습니다.' }
        ];

        qnas.forEach((qa, idx) => {
            const yPos = 1.9 + idx * 1.6;
            slide.addShape(pptx.shapes.RECTANGLE, {
                x: 0.8, y: yPos, w: 11.5, h: 1.4,
                fill: { color: BG_CARD }, line: { color: '334155', width: 1 }
            });
            slide.addText(qa.q, { x: 1.1, y: yPos + 0.15, w: 10.9, h: 0.35, fontSize: 11.5, color: ACCENT_BLUE, bold: true, fontFace: 'Arial' });
            slide.addText(qa.a, { x: 1.1, y: yPos + 0.55, w: 10.9, h: 0.7, fontSize: 10.5, color: TEXT_MAIN, lineSpacing: 18, fontFace: 'Arial' });
        });
    }

    // Generate timestamp for unique file versioning
    const now = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    const timestamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    
    // Check if custom name provided via CLI (e.g. node build_pptx.js v2)
    const customSuffix = process.argv[2] ? `_${process.argv[2]}` : `_${timestamp}`;
    const versionedFileName = `presentation${customSuffix}.pptx`;
    const versionedPath = path.resolve(__dirname, versionedFileName);
    const finalPath = path.resolve(__dirname, 'presentation_final.pptx');

    // Save timestamped version
    await pptx.writeFile({ fileName: versionedPath });
    console.log(`[Versioned] Presentation saved as: ${versionedFileName}`);

    // Also update presentation_final.pptx for convenience
    await pptx.writeFile({ fileName: finalPath });
    console.log(`[Latest] Presentation also updated at: presentation_final.pptx`);
}

createPresentation().catch(err => {
    console.error('Error generating presentation:', err);
    process.exit(1);
});
