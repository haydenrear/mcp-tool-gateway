package com.hayden.mcptoolgateway.kubernetes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hayden.persistence.models.AuditedEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;

/**
 * Stores the mapping of a user to an assigned MCP unit (k8s Deployment/Ingress)
 * so subsequent requests can be forwarded consistently to the same endpoint.
 *
 * Notes:
 * - We persist the resolved host (prefer ingress host) to avoid looking it up every time;
 *   this is refreshed whenever we reassign.
 * - We also store the Kubernetes namespace in case the gateway runs across multiple namespaces.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@Entity
@Table
public class UserMetadata extends AuditedEntity<String> {

    @Id
    @org.springframework.data.annotation.Id
    private String id;

    /**
     * The unique identifier for the user (e.g., subject or principal).
     * In this gateway it aligns with AuthResolver.resolveUser() which returns a bearer,
     * but callers should pass the stable id (e.g., subject) when persisting.
     */
    @Column(name = "user_id", nullable = false, unique = true, length = 512)
    private String userId;

    /**
     * The chosen K8s Deployment name for the unit (matches Helm unit fullname).
     */
    @Column(name = "unit_name", nullable = false, length = 253)
    private String unitName;

    /**
     * The Kubernetes namespace where the unit resources exist.
     */
    @Column(name = "k8s_namespace", nullable = false, length = 253)
    private String namespace;

    /**
     * Preferred host to reach this unit (Ingress host when available).
     * If null, callers may fallback to service DNS resolution.
     */
    @Column(name = "resolved_host", length = 2048)
    private String resolvedHost;

    /**
     * Optional: last time this assignment was validated or refreshed.
     */
    @Column(name = "last_validated_at")
    private OffsetDateTime lastValidatedAt;

    @Override
    public String equalsAndHashCodeId() {
        return id;
    }
}