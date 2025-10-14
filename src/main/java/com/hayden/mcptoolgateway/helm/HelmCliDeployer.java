package com.hayden.mcptoolgateway.helm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hayden.mcptoolgateway.config.HelmProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * HelmDeployer CLI implementation that shells out to "helm" via ProcessBuilder.
 *
 * Key behaviors:
 * - upgradeInstall(spec, values): writes a temporary JSON file (valid YAML subset) and passes it
 *   with "-f <file>". The JSON is compact to avoid quoting/whitespace issues.
 * - upgradeInstallWithUnitOverrides(spec, unitOverrides): uses "--set-json unitOverrides=<compact-json>"
 *   to set per-unit replica counts and annotations without materializing a values file.
 *
 * Notes:
 * - This class avoids external JSON/YAML libs by including a small internal JSON serializer that covers
 *   Map/List/String/Number/Boolean/null with minimal escaping rules.
 * - Commands are assembled using ProcessBuilder argument lists (no shell), so we avoid extra quoting needs.
 * - Stderr/stdout are captured concurrently to avoid deadlocks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelmCliDeployer implements HelmDeployer {

    private final HelmProperties helmProperties;
    private final ObjectMapper objectMapper;

    private String helmBinary;

    @PostConstruct
    public void init() {
        this.helmBinary = helmProperties.getBinary();
    }

    @Override
    public HelmResult upgradeInstall(ReleaseSpec spec, Map<String, Object> values) {
        Objects.requireNonNull(spec, "spec must not be null");
        final boolean hasValues = values != null && !values.isEmpty();

        try {
            final List<String> cmd = baseUpgradeInstall(spec);

            if (hasValues) {
                var tempValues = tempValuesJson(values);
                cmd.add("--set-json %s" + tempValues);
            }

            return runHelm(cmd, computeProcessTimeout(spec));
        } catch (Exception e) {
            log.error("Helm upgrade/install failed: {}", e.getMessage(), e);
            return HelmResult.fail(-1, "", "Exception: " + e.getMessage());
        }
    }

    public boolean isAvailable() {
        try {
            HelmResult r = runHelm(List.of(helmBinary, "version", "--short"), Duration.ofSeconds(10));
            return r.success();
        } catch (Exception e) {
            log.warn("Helm availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String version() {
        try {
            HelmResult r = runHelm(List.of(helmBinary, "version", "--short"), Duration.ofSeconds(10));
            if (r.success()) {
                return r.stdout() != null ? r.stdout().trim() : "unknown";
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    private List<String> baseUpgradeInstall(ReleaseSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(helmBinary);
        cmd.add("upgrade");
        cmd.add("--install");
        cmd.add(nonEmpty(spec.getReleaseName(), "releaseName"));
        cmd.add(nonEmpty(spec.getChartPath(), "chartPath"));

        if (spec.isWait()) {
            cmd.add("--wait");
        }

        cmd.add("--timeout");
        cmd.add(Math.max(1, spec.getTimeoutSeconds()) + "s");
        if (spec.getNamespace() != null && !spec.getNamespace().isBlank()) {
            cmd.add("-n");
            cmd.add(spec.getNamespace());
        }
        if (spec.extraArgsOrEmpty() != null && !spec.extraArgsOrEmpty().isEmpty()) {
            cmd.addAll(spec.extraArgsOrEmpty());
        }
        return cmd;
    }

    private Duration computeProcessTimeout(ReleaseSpec spec) {
        // Give the process itself a small buffer over Helm's --timeout to flush logs/etc.
        int sec = Math.max(1, spec.getTimeoutSeconds());
        return Duration.ofSeconds(Math.min(Integer.MAX_VALUE - 30, sec + 30L));
    }

    private static String nonEmpty(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return v;
    }

    private String tempValuesJson(Map<String, Object> values) throws Exception {
        String s = objectMapper.writeValueAsString(values);
        return s;
    }

    private HelmResult runHelm(List<String> command, Duration processTimeout) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Running helm: {}", String.join(" ", command));
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        // Inherit environment; user cluster config is assumed present in the pod or environment.
        pb.directory(new File("."));
        Process p = pb.start();

        StreamCollector out = new StreamCollector(p.getInputStream());
        StreamCollector err = new StreamCollector(p.getErrorStream());

        ExecutorService exec = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "helm-io");
            t.setDaemon(true);
            return t;
        });
        Future<String> outF = exec.submit(out);
        Future<String> errF = exec.submit(err);

        boolean finished;
        try {
            finished = p.waitFor(processTimeout.getSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        if (!finished) {
            p.destroyForcibly();
            shutdown(exec);
            return HelmResult.fail(124, "", "helm process timed out after " + processTimeout.getSeconds() + "s");
        }

        int code = p.exitValue();
        String stdout = safeGet(outF);
        String stderr = safeGet(errF);
        shutdown(exec);

        if (code == 0) {
            return HelmResult.ok(stdout);
        } else {
            return HelmResult.fail(code, stdout, stderr);
        }
    }

    private static void shutdown(ExecutorService exec) {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exec.shutdownNow();
        }
    }

    private static String safeGet(Future<String> f) {
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }


    private static final class StreamCollector implements Callable<String> {
        private final InputStream in;

        private StreamCollector(InputStream in) {
            this.in = in;
        }

        @Override
        public String call() throws Exception {
            StringBuilder sb = new StringBuilder(512);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
            } catch (Exception e) {
                // Best effort; return what we have
            }
            return sb.toString();
        }
    }


}