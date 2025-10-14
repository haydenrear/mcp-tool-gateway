package com.hayden.mcptoolgateway.config;

import com.hayden.mcptoolgateway.helm.HelmDeployer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Bindable configuration properties for helm interactions initiated by the gateway.
 */
@Data
@ConfigurationProperties(prefix = "helm")
@Component
public class HelmProperties {
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
     *
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

}
