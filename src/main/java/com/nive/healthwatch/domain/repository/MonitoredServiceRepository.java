package com.nive.healthwatch.domain.repository;

import com.nive.healthwatch.domain.MonitoredService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author nive
 * @class MonitoredServiceRepository
 * @desc monitored_service 리포지토리. 스케줄러가 findByEnabledTrue() 로 감시 대상을 조회한다.
 * @since 2026-07-06
 */
public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, Long> {

    List<MonitoredService> findByEnabledTrue();

    Optional<MonitoredService> findByName(String name);
}
