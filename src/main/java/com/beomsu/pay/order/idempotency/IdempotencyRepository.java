package com.beomsu.pay.order.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndApiPathAndHttpMethod(
            String idempotencyKey, String apiPath, String httpMethod);
}
