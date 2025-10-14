package com.hayden.mcptoolgateway.helm;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * Spring configuration for wiring a HelmDeployer and binding basic Helm configuration properties.
 *
 * Usage:
 * - Configure properties (application.yml/properties) under the "helm" prefix:
 *
 *   helm:
 *     binary: "helm"                  # Path or name of the helm binary (default: "helm")
 *     chartPath: "deploy-helm"        # Filesystem path to the chart root
 *     namespace: "default"            # Optional namespace override for releases
 *     releaseName: "commit-diff-context"  # Optional default Helm release name
 *     wait: true                      # Whether to pass --wait to helm
 *     timeoutSeconds: 300             # Helm --timeout value in seconds
 *     extraArgs: []                   # Additional args to append (e.g., ["--debug"])
 *
 * - Build a ReleaseSpec from injected HelmProperties to execute upgrades/installs:
 *
 *   var spec = helmProperties.toReleaseSpec("cdc-c1");
 *   helmDeployer.upgradeInstallWithUnitOverrides(spec, overrides);
 */
@Configuration
@EnableConfigurationProperties(HelmConfig.HelmProperties.class)
public class HelmConfig {

    /**
     * Provides a HelmDeployer backed by the helm CLI via ProcessBuilder.
     * You can override this bean elsewhere to swap implementations.
     */
    @Bean
    @ConditionalOnMissingBean(HelmDeployer.class)
    public HelmDeployer helmDeployer(HelmProperties props) {
        String binary = (props.getBinary() == null || props.getBinary().isBlank()) ? "helm" : props.getBinary();
        return new HelmCliDeployer(binary);
    }

    /**
     * Bindable configuration properties for helm interactions initiated by the gateway.
     */
    @Data
    @ConfigurationProperties(prefix = "helm")
    public static class HelmProperties {
        /**
         * Path or executable name of the helm binary. Default resolves via PATH.
         */
        private String binary = "helm";

        /**
         * Filesystem path to the Helm chart used for deploying cells/units.
         * This path should point at the chart root (where Chart.yaml is located).
         */
        private String chartPath = "deploy-helm";

        /**
         * Kubernetes namespace to target for releases.
         * If blank, helm/kubeconfig default namespace resolution is used.
         */
        private String namespace;

        /**
         * Default Helm release name to use when one is not explicitly provided.
         */
        private String releaseName = "commit-diff-context";

        /**
         * Whether to pass "--wait" to helm upgrade --install.
         */
        private boolean wait = true;

        /**
         * Helm "--timeout" value (seconds). Also used to size the process execution timeout.
         */
        private int timeoutSeconds = 300;

        /**
         * Additional flags appended to the helm command (e.g., ["--debug"]).
         */
        private List<String> extraArgs = Collections.emptyList();

        /**
         * Convenience to build a ReleaseSpec for a given release name from the configured defaults.
         * @param releaseName helm release name (e.g., "cdc-c0", "cdc-c1")
         */
        public HelmDeployer.ReleaseSpec toReleaseSpec(String releaseName) {
            HelmDeployer.ReleaseSpec.ReleaseSpecBuilder b = HelmDeployer.ReleaseSpec.builder()
                    .releaseName(releaseName)
                    .namespace(namespace)
                    .chartPath(chartPath)
                    .wait(wait)
                    .timeoutSeconds(timeoutSeconds);

            if (extraArgs != null) {
                for (String arg : extraArgs) {
                    if (arg != null && !arg.isBlank()) {
                        b.extraArg(arg);
                    }
                }
            }
            return b.build();
        }

        /**
         * Convenience to build a ReleaseSpec using the configured default releaseName.
         * Useful for operations that target the primary release without computing a name per call.
         */
        public HelmDeployer.ReleaseSpec defaultReleaseSpec() {
            return toReleaseSpec(releaseName);
        }
    }
}