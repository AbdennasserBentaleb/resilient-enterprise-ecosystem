package com.portfolio.ecosystem.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;

/**
 * Intercepts every repository method call and injects the current tenant ID
 * into the Postgres session via SET LOCAL. This triggers the RLS policy that
 * filters rows by app.current_tenant, giving us true DB-level isolation.
 *
 * Note: SET LOCAL is scoped to the current transaction, so it's reset
 * automatically at the end of each request. No manual cleanup needed.
 */
@Aspect
@Component
public class TenantConnectionAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("execution(* com.portfolio.ecosystem.repository..*(..))")
    public Object setTenantContext(ProceedingJoinPoint pjp) throws Throwable {
        UUID tenantId = TenantContext.getCurrentTenant();

        if (tenantId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.doWork(connection -> {
                // This is the magic line - Postgres reads this in the RLS policy
                // (app.current_tenant) and filters accordingly
                try (java.sql.Statement stmt = connection.createStatement()) {
                    stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
                }
            });
        }

        return pjp.proceed();
    }
}
