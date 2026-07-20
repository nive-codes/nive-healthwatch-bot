package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.AiReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

/**
 * @author nive
 * @class AiReportRepository
 * @desc ai_report 리포지토리. retention 정리용 삭제 쿼리 포함.
 * @since 2026-07-06
 */
public interface AiReportRepository extends JpaRepository<AiReport, Long> {

    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
