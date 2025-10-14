package com.hayden.mcptoolgateway.kubernetes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA repository for persisting and retrieving UserMetadata assignments.
 */
@Repository
public interface UserMetadataRepository extends JpaRepository<UserMetadata, String> {

    Optional<UserMetadata> findByUserId(String userId);

    List<UserMetadata> findByUnitName(String unitName);
}