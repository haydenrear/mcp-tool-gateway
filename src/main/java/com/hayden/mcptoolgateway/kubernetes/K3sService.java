package com.hayden.mcptoolgateway.kubernetes;

import com.hayden.mcptoolgateway.security.AuthResolver;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import java.net.URI;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.hayden.mcptoolgateway.helm.HelmDeployer;
import com.hayden.mcptoolgateway.config.HelmProperties;

@Component
@Slf4j
public class K3sService {


    // Annotation keys used to track capacity/assignment on the Deployment
    private static final String ANN_MAX_USERS = "unit.hayden/maxUsers";
    private static final String ANN_ASSIGNED_USERS = "unit.hayden/assignedUsers";
    private static final String ANN_USERS_PER_REPLICA = "unit.hayden/usersPerReplica";

    // Label used by the Helm chart to mark unit Deployments
    private static final String LABEL_COMPONENT = "app.kubernetes.io/component";
    private static final String COMPONENT_UNIT = "unit";

    // Env-based defaults
    private static final int DEFAULT_USERS_PER_REPLICA = getEnvInt("GATEWAY_UNIT_USERS_PER_REPLICA", 10);
    private static final int DEFAULT_MAX_USERS = getEnvInt("GATEWAY_UNIT_MAX_USERS", Integer.MAX_VALUE);

    private final Map<String, String> userAssignments = Collections.synchronizedMap(new WeakHashMap<>());

    private volatile KubernetesClient client;

    @Autowired
    private UserMetadataRepository userMetadataRepository;
    @Autowired
    private HelmDeployer helmDeployer;
    @Autowired
    private HelmProperties helmProperties;

    private final ReadWriteLock assignLock = new ReentrantReadWriteLock();

    private KubernetesClient client() {
        KubernetesClient c = this.client;
        if (c == null) {
            try {
                assignLock.writeLock().lock();
                c = this.client;
                if (c == null) {
                    c = new KubernetesClientBuilder().build();
                    this.client = c;
                }
            } finally {
                assignLock.writeLock().unlock();
            }
        }
        return c;
    }

    private String namespace() {
        String ns = client().getNamespace();
        return ns != null && !ns.isBlank() ? ns : "default";
    }

    public record K3sDeployResult(String err,
                                  boolean success,
                                  String host) {
        public K3sDeployResult(boolean success, String err) {
            this(err, success, "");
        }

        public K3sDeployResult(String err) {
            this(err, false, null);
        }

        public static K3sDeployResult ok(String host) {
            return new K3sDeployResult(null, true, host);
        }

        public static K3sDeployResult err(String s) {
            return new K3sDeployResult(s);
        }
    }

    /**
     * Find or assign a unit for the current authenticated user, scale replicas as needed,
     * and return a reachable host (prefer ingress, otherwise service DNS).
     */
    public K3sDeployResult doDeployGetValidDeployment(String user) {

        try {
            // If we've already assigned a unit for this user in this gateway instance, reuse it.
            String already = userAssignments.get(user);
            if (already != null) {
                String host = resolveUnitEndpoint(already);
                if (host != null) {
                    return K3sDeployResult.ok(host);
                } else {
                    // If the unit disappeared, drop the assignment and reassign below.
                    userAssignments.remove(user);
                }
            }

            var existing = userMetadataRepository.findByUserId(user);

            if (existing.isPresent()) {
                var meta = existing.get();
                String host = meta.getResolvedHost();
                if (host == null || host.isBlank()) {
                    host = resolveUnitEndpoint(meta.getUnitName());
                }
                if (host != null) {
                    meta.setResolvedHost(host);
                    meta.setLastValidatedAt(OffsetDateTime.now());
                    userMetadataRepository.save(meta);
                    userAssignments.put(user, meta.getUnitName());
                    return K3sDeployResult.ok(host);
                } else {
                    // mapping invalid; remove and reassign
                    userMetadataRepository.delete(meta);
                    userAssignments.remove(user);
                }
            }

            try {
                assignLock.readLock().lock();
                List<Deployment> units = client().apps().deployments()
                        .inNamespace(namespace())
                        .withLabel(LABEL_COMPONENT, COMPONENT_UNIT)
                        .list()
                        .getItems();

                if (CollectionUtils.isEmpty(units)) {
                    return new K3sDeployResult("No unit Deployments found in namespace " + namespace());
                }

                // Choose the unit with capacity and lowest load
                Deployment chosen = chooseUnit(units);
                if (chosen == null) {
                    return new K3sDeployResult("No unit capacity available; consider provisioning additional units.");
                }

                // Update assigned users and scale replicas if needed
                String chosenName = chosen.getMetadata().getName();
                if (!updateAssignmentAndScale(chosen)) {
                    return new K3sDeployResult("Failed to update assignment/scale for unit " + chosenName);
                }

                String host = resolveUnitEndpoint(chosenName);
                if (host == null) {
                    return new K3sDeployResult("Could not resolve a reachable endpoint for unit " + chosenName);
                }

                userAssignments.put(user, chosenName);

                // Persist or update DB mapping
                var meta = userMetadataRepository.findByUserId(user)
                        .orElseGet(() -> UserMetadata.builder()
                                .userId(user)
                                .id(user)
                                .build());
                meta.setUnitName(chosenName);
                meta.setNamespace(namespace());
                meta.setResolvedHost(host);
                meta.setLastValidatedAt(OffsetDateTime.now());
                userMetadataRepository.save(meta);

                return K3sDeployResult.ok(host);
            } finally {
                assignLock.readLock().unlock();
            }
        } catch (Exception e) {
            log.error("K3sService.doDeployGetValidDeployment failed: {}", e.getMessage(), e);
            return new K3sDeployResult("Exception: " + e.getMessage());
        }
    }

    /**
     * Choose the unit Deployment with available capacity and minimal load.
     * Load heuristic: assignedUsers / max(1, replicas * usersPerReplica).
     */
    private Deployment chooseUnit(List<Deployment> units) {
        Deployment best = null;
        double bestScore = Double.MAX_VALUE;

        for (Deployment d : units) {
            Map<String, String> ann = Optional.ofNullable(d.getMetadata().getAnnotations()).orElse(Collections.emptyMap());
            int assigned = parseInt(ann.get(ANN_ASSIGNED_USERS), 0);
            int maxUsers = parseInt(ann.get(ANN_MAX_USERS), DEFAULT_MAX_USERS);
            int upr = parseInt(ann.get(ANN_USERS_PER_REPLICA), DEFAULT_USERS_PER_REPLICA);
            int replicas = Optional.ofNullable(d.getSpec()).map(s -> s.getReplicas() == null ? 0 : s.getReplicas()).orElse(0);

            if (assigned >= maxUsers) {
                // At capacity, skip
                continue;
            }

            int capacityNow = Math.max(1, Math.max(1, replicas) * Math.max(1, upr));
            double score = (double) assigned / (double) capacityNow;

            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /**
     * Increment assignedUsers and scale replicas to meet required capacity:
     * requiredReplicas = ceil(assignedUsers / usersPerReplica)
     */
    private boolean updateAssignmentAndScale(Deployment current) {
        String name = current.getMetadata().getName();

        Map<String, String> ann = current.getMetadata().getAnnotations();
        if (ann == null) ann = new HashMap<>();
        int assigned = parseInt(ann.get(ANN_ASSIGNED_USERS), 0) + 1; // assign current user
        int upr = parseInt(ann.get(ANN_USERS_PER_REPLICA), DEFAULT_USERS_PER_REPLICA);
        int maxUsers = parseInt(ann.get(ANN_MAX_USERS), DEFAULT_MAX_USERS);

        if (assigned > maxUsers) {
            return false;
        }

        int requiredReplicas = Math.max(1, (int) Math.ceil((double) assigned / Math.max(1, upr)));
        int currentReplicas = Optional.ofNullable(current.getSpec()).map(s -> s.getReplicas() == null ? 0 : s.getReplicas()).orElse(0);

        try {
            // Apply desired state via Helm upgrade with per-unit overrides
            assignLock.writeLock().lock();
            int replicasToSet = Math.max(currentReplicas, requiredReplicas);

            HelmDeployer.UnitOverride override = HelmDeployer.UnitOverride.builder()
                    .replicaCount(replicasToSet)
                    .annotation(ANN_ASSIGNED_USERS, String.valueOf(assigned))
                    .annotation(ANN_USERS_PER_REPLICA, String.valueOf(upr))
                    .build();

            // Only set maxUsers if previously present or env default is not MAX_VALUE
            if (ann.containsKey(ANN_MAX_USERS) || DEFAULT_MAX_USERS != Integer.MAX_VALUE) {
                override = HelmDeployer.UnitOverride.builder()
                        .replicaCount(replicasToSet)
                        .annotation(ANN_ASSIGNED_USERS, String.valueOf(assigned))
                        .annotation(ANN_USERS_PER_REPLICA, String.valueOf(upr))
                        .annotation(ANN_MAX_USERS, String.valueOf(maxUsers))
                        .build();
            }

            Map<String, HelmDeployer.UnitOverride> overrides = new HashMap<>();
            overrides.put(name, override);

            String cell = Optional.ofNullable(current.getMetadata().getLabels())
                    .map(m -> m.get("unit.hayden/cell"))
                    .orElse(null);
            String releaseName = (cell != null && !cell.isBlank())
                    ? "cell-" + cell
                    : helmProperties.getReleaseName();

            // Build values for helm upgrade to set both instanceOverride and unitOverrides
            Map<String, Object> values = new HashMap<>();
            values.put("instanceOverride", releaseName);

            Map<String, Object> unitOverridesVals = new HashMap<>();
            Map<String, Object> one = new HashMap<>();
            one.put("replicaCount", replicasToSet);
            Map<String, String> anns = new HashMap<>();
            anns.put(ANN_ASSIGNED_USERS, String.valueOf(assigned));
            anns.put(ANN_USERS_PER_REPLICA, String.valueOf(upr));
            if (ann.containsKey(ANN_MAX_USERS) || DEFAULT_MAX_USERS != Integer.MAX_VALUE) {
                anns.put(ANN_MAX_USERS, String.valueOf(maxUsers));
            }
            one.put("annotations", anns);
            unitOverridesVals.put(name, one);
            values.put("unitOverrides", unitOverridesVals);

            HelmDeployer.HelmResult res = helmDeployer.upgradeInstall(
                    helmProperties.toReleaseSpec(releaseName),
                    values
            );

            if (!res.success()) {
                log.error("Helm upgrade failed for unit {}: code={}, stderr={}", name, res.exitCode(), res.stderr());
                return false;
            }

            log.info("Updated unit {} via Helm: assignedUsers={}, usersPerReplica={}, replicas={}->{}",
                    name, assigned, upr, currentReplicas, replicasToSet);
            return true;
        } catch (Exception e) {
            log.error("Failed to apply Helm upgrade for unit {}: {}", name, e.getMessage(), e);
            return false;
        } finally {
            assignLock.writeLock().unlock();
        }
    }

    /**
     * Resolve the endpoint for a unit:
     * - Prefer Ingress host. If TLS configured, return https://host, otherwise http://host.
     * - Fallback to in-cluster Service DNS: http://<svc>.<ns>.svc.cluster.local:<port>
     */
    private String resolveUnitEndpoint(String unitName) {
        // Prefer ingress
        try {
            Ingress ing = client().network().v1().ingresses()
                    .inNamespace(namespace())
                    .withName(unitName)
                    .get();

            if (ing != null && ing.getSpec() != null && !CollectionUtils.isEmpty(ing.getSpec().getRules())) {
                String host = Optional.ofNullable(ing.getSpec().getRules())
                        .stream()
                        .flatMap(Collection::stream)
                        .map(IngressRule::getHost)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                if (host != null && !host.isBlank()) {
                    boolean hasTls = ing.getSpec().getTls() != null && !ing.getSpec().getTls().isEmpty();
                    String scheme = hasTls ? "https" : "http";
                    return URI.create(scheme + "://" + host).toString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Ingress for unit {}: {}", unitName, e.getMessage());
        }

        // Fallback to in-cluster Service DNS
        try {
            Service svc = client().services()
                    .inNamespace(namespace())
                    .withName(unitName)
                    .get();

            if (svc != null && svc.getSpec() != null) {
                Integer port = resolveHttpPort(svc);
                if (port == null) {
                    // pick first port if named not found
                    port = Optional.ofNullable(svc.getSpec().getPorts())
                            .filter(p -> !p.isEmpty())
                            .map(p -> p.get(0).getPort())
                            .orElse(8080);
                }
                String dns = "%s.%s.svc.cluster.local".formatted(unitName, namespace());
                return "http://%s:%d".formatted(dns, port);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Service for unit {}: {}", unitName, e.getMessage());
        }

        return null;
    }

    private Integer resolveHttpPort(Service svc) {
        if (svc.getSpec().getPorts() == null)
            return null;
        // Prefer port named "http"
        return svc.getSpec().getPorts().stream()
                .filter(p -> "http".equalsIgnoreCase(p.getName()))
                .map(this::portNumber)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Integer portNumber(ServicePort p) {
        // ServicePort port is an int, targetPort can be IntOrString
        if (p.getPort() != null) {
            return p.getPort();
        }
        IntOrString t = p.getTargetPort();
        if (t == null) return null;
        if (t.getIntVal() != null)
            return t.getIntVal();
        try {
            return Integer.parseInt(t.getStrVal());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int getEnvInt(String key, int def) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
