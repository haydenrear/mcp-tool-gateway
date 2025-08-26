package com.hayden.mcptoolgateway.config;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

@Slf4j
public class KillCdcInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @SneakyThrows
    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.info("Killing cdc.");
        var environment = applicationContext.getEnvironment();
        var props = new PathMatchingResourcePatternResolver().getResource(environment.getProperty("gateway.kill-script")).getFile();
        if (props.exists()) {
            log.info("Found kill script in properties.");
            doKill(props);
        } else {
            var f = new File("");
            if (!f.toPath().resolve("kill-cdc.sh").toFile().exists()
                    && f.toPath().getParent().resolve("kill-cdc.sh").toFile().exists()) {
                f = f.toPath().getParent().resolve("kill-cdc.sh").toFile();
            } else if (f.toPath().resolve("kill-cdc.sh").toFile().exists()) {
                f = f.toPath().resolve("kill-cdc.sh").toFile();
            } else if (f.toPath().resolve("kill-cdc.sh").toFile().exists()) {

            }
            if (f.exists()) {
                doKill(f);
            }
        }
    }

    private static void doKill(File f) {
        try {
            log.info("Killing cdc.");
            Process bash = new ProcessBuilder("bash", f.getAbsolutePath())
                    .start();
            bash.waitFor();
            try(var b = new BufferedReader(new InputStreamReader(bash.getInputStream()))) {
                var l = b.lines().collect(Collectors.joining(System.lineSeparator())) ;
                log.info("Attempted to stop existing:\n{}", l);
            }
        } catch (InterruptedException |
                 IOException e) {
            log.error("Failed to kill existing...");
        }
    }

}
