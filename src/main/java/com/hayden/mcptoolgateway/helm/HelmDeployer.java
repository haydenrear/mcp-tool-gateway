package com.hayden.mcptoolgateway.helm;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Abstraction to manage Helm releases from the gateway.
 *
 * Intent:
 * - Keep K3sService assignment/selection logic unchanged.
 * - Swap direct Kubernetes scaling/patching for Helm upgrades that carry the desired state
 *   (e.g., per-unit replica counts and unit-level annotations) in values.
 * - Allow easy swapping of the underlying implementation (CLI via ProcessBuilder now, library later).
 *
 * Expected CLI shape for implementations:
 *   helm upgrade --install <releaseName> <chartPath> -n <namespace> --wait --timeout <timeout> \
 *     --set-json unitOverrides='{"<unitFullname>":{"replicaCount":2,"annotations":{"unit.hayden/assignedUsers":"5"}}}'
 *
 * Notes:
 * - The chart supports a values map "unitOverrides" keyed by the rendered unit fullname (as produced by the chart).
 *   Each entry may carry "replicaCount" and an "annotations" map that are applied to the unit Deployment.
 * - The chart also allows toggling non-unit components independently (gateway/rbac/authorization/rootIngress/tlsCertificate).
 */
public interface HelmDeployer {

    /**
     * Upgrade (or install) a release with arbitrary top-level value overrides.
     * Implementations should execute an idempotent "helm upgrade --install" and return the result.
     *
     * Common usage:
     * - Setting component toggles (e.g., components.gateway.enabled=false)
     * - Supplying a seed list of units in .Values.units
     *
     * @param spec   Release metadata (name, namespace, chart path, timeout, and extra args).
     * @param values Arbitrary key/value overrides mapped to .Values (merged at top-level).
     * @return result of the helm invocation
     */
    HelmResult upgradeInstall(ReleaseSpec spec, Map<String, Object> values);

    /**
     * Returns a short version string for diagnostics (e.g., "v3.14.0").
     * Implementations may return "unknown" when detection is not available.
     */
    String version();

    /**
         * Canonical result for a helm invocation.
         */
    record HelmResult(boolean success, int exitCode,
                      String stdout, String stderr) {
            public static HelmResult ok(String stdout) {
                return new HelmResult(true, 0, stdout, "");
            }

            public static HelmResult fail(int exitCode, String stdout, String stderr) {
                return new HelmResult(false, exitCode, stdout, stderr);
            }
        }

    /**
     * Release metadata used by all operations.
     * - releaseName: Helm release name (e.g., "cdc-c0", "cdc-c1").
     * - namespace: Kubernetes namespace to target.
     * - chartPath: Filesystem path to the chart root (e.g., deploy-helm).
     * - wait: Whether to pass "--wait" (recommended true for deterministic readiness).
     * - timeoutSeconds: Helm "--timeout" in seconds (e.g., 300 for 5m).
     * - extraArgs: Any additional flags needed by an operator (e.g., "--debug").
     */
    @Value
    @Builder
    class ReleaseSpec {
        String releaseName;
        String namespace;
        String chartPath;
        @Builder.Default
        boolean wait = true;
        @Builder.Default
        int timeoutSeconds = 300;
        @Singular
        List<String> extraArgs;

        public List<String> extraArgsOrEmpty() {
            return extraArgs != null ? extraArgs : Collections.emptyList();
        }
    }

    /**
     * Per-unit override payload.
     * - replicaCount: desired replicas for the unit Deployment.
     * - annotations: arbitrary metadata applied at Deployment.metadata.annotations.
     */
    @Value
    @Builder
    class UnitOverride {
        Integer replicaCount;
        @Singular("annotation")
        Map<String, String> annotations;
    }
}