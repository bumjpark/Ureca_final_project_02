package com.ureca.myureca.repository;

import com.ureca.myureca.domain.notification.MockNotificationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockNotificationLogRepository extends JpaRepository<MockNotificationLog, Long> {

    Page<MockNotificationLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<MockNotificationLog> findByCouponPolicyIdOrderByCreatedAtDesc(Long couponPolicyId, Pageable pageable);
}
