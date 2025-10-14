package com.hayden.mcptoolgateway.helm;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
public class HelmCliDeployer implements HelmDeployer {

    private final String helmBinary;

    /**
     * Construct with default binary name "helm" (resolved via PATH).
     */
    public HelmCliDeployer() {
        this("helm");
    }

    /**
     * Construct with an explicit helm binary path/name.
     */
    public HelmCliDeployer(@NonNull String helmBinary) {
        this.helmBinary = helmBinary;
    }

    @Override
    public Result upgradeInstall(ReleaseSpec spec, Map<String, Object> values) {
        Objects.requireNonNull(spec, "spec must not be null");
        final boolean hasValues = values != null && !values.isEmpty();

        File tempValues = null;
        try {
            final List<String> cmd = baseUpgradeInstall(spec);

            if (hasValues) {
                tempValues = createTempValuesFile(values);
                cmd.add("-f");
                cmd.add(tempValues.getAbsolutePath());
            }

            return runHelm(cmd, computeProcessTimeout(spec));
        } catch (Exception e) {
            log.error("Helm upgrade/install failed: {}", e.getMessage(), e);
            return Result.fail(-1, "", "Exception: " + e.getMessage());
        } finally {
            if (tempValues != null && tempValues.exists()) {
                try {
                    Files.deleteIfExists(tempValues.toPath());
                } catch (Exception ignore) {
                    // best effort cleanup
                }
            }
        }
    }

    @Override
    public Result upgradeInstallWithUnitOverrides(ReleaseSpec spec, Map<String, UnitOverride> unitOverrides) {
        Objects.requireNonNull(spec, "spec must not be null");
        try {
            final List<String> cmd = baseUpgradeInstall(spec);

            // Build compact JSON for: unitOverrides = { "<unitFullname>": { replicaCount, annotations }, ... }
            final String json = compactJson(normalizeUnitOverrides(unitOverrides));
            cmd.add("--set-json");
            cmd.add("unitOverrides=" + json);

            return runHelm(cmd, computeProcessTimeout(spec));
        } catch (Exception e) {
            log.error("Helm upgrade/install with unitOverrides failed: {}", e.getMessage(), e);
            return Result.fail(-1, "", "Exception: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            Result r = runHelm(List.of(helmBinary, "version", "--short"), Duration.ofSeconds(10));
            return r.success();
        } catch (Exception e) {
            log.warn("Helm availability check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String version() {
        try {
            Result r = runHelm(List.of(helmBinary, "version", "--short"), Duration.ofSeconds(10));
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
        // Helm timeout applies to resource readiness; we also apply a process timeout as a small buffer.
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

    private static File createTempValuesFile(Map<String, Object> values) throws Exception {
        File f = File.createTempFile("helm-values-", ".json");
        // JSON is valid YAML; Helm will parse it as values.
        String json = compactJson(values);
        try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            w.write(json);
            w.flush();
        }
        return f;
    }

    private Result runHelm(List<String> command, Duration processTimeout) throws Exception {
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
            return Result.fail(124, "", "helm process timed out after " + processTimeout.getSeconds() + "s");
        }

        int code = p.exitValue();
        String stdout = safeGet(outF);
        String stderr = safeGet(errF);
        shutdown(exec);

        if (code == 0) {
            return Result.ok(stdout);
        } else {
            return Result.fail(code, stdout, stderr);
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

    private static Map<String, Object> normalizeUnitOverrides(Map<String, UnitOverride> unitOverrides) {
        if (unitOverrides == null || unitOverrides.isEmpty()) {
            return Collections.emptyMap();
        }
        // Build: { "<unitFullname>": { "replicaCount": n, "annotations": { ... } } }
        ConcurrentMapBuilder<String, Object> root = new ConcurrentMapBuilder<>();
        for (Map.Entry<String, UnitOverride> e : unitOverrides.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank() || e.getValue() == null) continue;

            ConcurrentMapBuilder<String, Object> one = new ConcurrentMapBuilder<>();
            if (e.getValue().getReplicaCount() != null) {
                one.put("replicaCount", e.getValue().getReplicaCount());
            }
            if (e.getValue().getAnnotations() != null && !e.getValue().getAnnotations().isEmpty()) {
                one.put("annotations", e.getValue().getAnnotations());
            }
            root.put(key, one.build());
        }
        return root.build();
    }

    // -----------------------------
    // Minimal JSON serializer (compact)
    // -----------------------------

    private static String compactJson(Object obj) {
        StringBuilder sb = new StringBuilder(256);
        writeJson(sb, obj);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder out, Object v) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof String s) {
            out.append('"').append(escapeJson(s)).append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            out.append(String.valueOf(v));
        } else if (v instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                if (!(k instanceof String)) continue;
                if (!first) out.append(',');
                out.append('"').append(escapeJson((String) k)).append("\":");
                writeJson(out, e.getValue());
                first = false;
            }
            out.append('}');
        } else if (v instanceof Iterable<?> it) {
            out.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) out.append(',');
                writeJson(out, x);
                first = false;
            }
            out.append(']');
        } else if (v.getClass().isArray()) {
            // Simple array handling via reflection to a List
            Object[] arr = (Object[]) v;
            out.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) out.append(',');
                writeJson(out, arr[i]);
            }
            out.append(']');
        } else {
            // Fallback to string
            out.append('"').append(escapeJson(String.valueOf(v))).append('"');
        }
    }

    private static String escapeJson(String s) {
        StringBuilder b = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    // -----------------------------
    // Stream collector
    // -----------------------------

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

    // -----------------------------
    // Small concurrent map builder
    // -----------------------------

    private static final class ConcurrentMapBuilder<K, V> {
        private final ConcurrentMap<K, V> map = new ConcurrentHashMap<>();
        ConcurrentMapBuilder<K, V> put(K k, V v) {
            if (k != null && v != null) {
                map.put(k, v);
            }
            return this;
        }
        Map<K, V> build() {
            return map;
        }
    }
}