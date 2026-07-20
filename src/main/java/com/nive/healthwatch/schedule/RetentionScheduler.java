package com.nive.healthwatch.schedule;

import com.nive.healthwatch.config.HealthWatchProperties;
import com.nive.healthwatch.domain.repository.AiReportRepository;
import com.nive.healthwatch.domain.repository.AnomalyEventRepository;
import com.nive.healthwatch.domain.repository.HealthDbSnapshotRepository;
import com.nive.healthwatch.domain.repository.HealthSnapshotRepository;
import com.nive.healthwatch.domain.repository.NotificationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * @author nive
 * @class RetentionScheduler
 * @desc H2 파일이 커지지 않도록 매일 새벽 오래된 데이터를 정리한다(retention 정책).
 * @since 2026-07-06
 */
@Slf4j
@Component
public class RetentionScheduler {

    private final HealthWatchProperties.Retention retention;
    private final HealthSnapshotRepository snapshotRepo;
    private final HealthDbSnapshotRepository dbSnapshotRepo;
    private final AnomalyEventRepository anomalyRepo;
    private final AiReportRepository aiReportRepo;
    private final NotificationLogRepository notificationLogRepo;

    public RetentionScheduler(HealthWatchProperties props,
                              HealthSnapshotRepository snapshotRepo,
                              HealthDbSnapshotRepository dbSnapshotRepo,
                              AnomalyEventRepository anomalyRepo,
                              AiReportRepository aiReportRepo,
                              NotificationLogRepository notificationLogRepo) {
        this.retention = props.getRetention();
        this.snapshotRepo = snapshotRepo;
        this.dbSnapshotRepo = dbSnapshotRepo;
        this.anomalyRepo = anomalyRepo;
        this.aiReportRepo = aiReportRepo;
        this.notificationLogRepo = notificationLogRepo;
    }

    @Scheduled(cron = "${health-watch.retention-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void purgeOld() {
        LocalDateTime now = LocalDateTime.now();
        long s = snapshotRepo.deleteByCollectedAtBefore(now.minusDays(retention.getHealthSnapshotDays()));
        long db = dbSnapshotRepo.deleteByCollectedAtBefore(now.minusDays(retention.getHealthDbSnapshotDays()));
        long a = anomalyRepo.deleteByLastDetectedAtBefore(now.minusDays(retention.getAnomalyEventDays()));
        long ai = aiReportRepo.deleteByCreatedAtBefore(now.minusDays(retention.getAiReportDays()));
        long n = notificationLogRepo.deleteBySentAtBefore(now.minusDays(retention.getNotificationLogDays()));
        log.info("[Retention] purge snapshot={} dbSnapshot={} anomaly={} aiReport={} notif={}", s, db, a, ai, n);
    }
}
