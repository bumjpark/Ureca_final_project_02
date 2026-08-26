package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.CouponHistoryResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponHistoryService {

    private final CouponIssueRepository couponIssueRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional(readOnly = true)
    public List<CouponHistoryResponse> getCouponHistory(Long couponIssueId) {
        if (!couponIssueRepository.existsById(couponIssueId)) {
            throw new CouponIssueNotFoundException(couponIssueId);
        }

        return couponHistoryRepository.findByCouponIssueIdOrderByCreatedAtAsc(couponIssueId)
                .stream()
                .map(CouponHistoryResponse::from)
                .toList();
    }
}
