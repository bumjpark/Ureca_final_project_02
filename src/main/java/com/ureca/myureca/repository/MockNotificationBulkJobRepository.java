package com.ureca.myureca.repository;

import com.ureca.myureca.domain.notification.MockNotificationBulkJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MockNotificationBulkJobRepository extends JpaRepository<MockNotificationBulkJob, Long> {

    Page<MockNotificationBulkJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<MockNotificationBulkJob> findByCouponPolicyIdOrderByCreatedAtDesc(Long couponPolicyId, Pageable pageable);
}
