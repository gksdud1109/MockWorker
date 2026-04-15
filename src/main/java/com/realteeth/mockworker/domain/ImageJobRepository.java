package com.realteeth.mockworker.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageJobRepository extends JpaRepository<ImageJob, String> {

    Optional<ImageJob> findByClientRequestKey(String clientRequestKey);

    @Query("""
            select j from ImageJob j
             where j.status = :status
               and j.nextAttemptAt <= :now
             order by j.nextAttemptAt asc
            """)
    List<ImageJob> findDueByStatus(@Param("status") JobStatus status,
                                   @Param("now") Instant now,
                                   Pageable pageable);
}
