package com.ureca.myureca.repository;

import com.ureca.myureca.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
