package com.example.apiverification.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.apiverification.entity.User;

/**
 * All email arguments are expected to be normalized already (see {@code UserMapper#normalizeEmail}).
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Used on update: is this email taken by a <em>different</em> user? */
    boolean existsByEmailAndIdNot(String email, Long id);
}
