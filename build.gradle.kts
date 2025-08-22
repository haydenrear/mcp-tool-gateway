plugins {
    id("com.hayden.mcp")
    id("com.hayden.spring-app")
    id("com.hayden.observable-app")
    id("com.hayden.dgs-graphql")
    id("com.hayden.log")
    id("com.hayden.docker-compose")
}

group = "com.hayden"
version = "1.0.0"

tasks.register("prepareKotlinBuildScriptModel") {}

dependencies {
    implementation(project(":utilitymodule"))
    implementation(project(":graphql"))
    implementation(project(":commit-diff-model"))
    implementation(project(":tracing"))
    implementation(project(":test-mcp-server"))
}

val projDir = layout.projectDirectory

tasks.register<Copy>("copyTestMcpServer") {
    doFirst {
        delete(file(projDir).resolve("ctx_bin/test-mcp-server.jar"))
    }

    dependsOn(project(":test-mcp-server").tasks.named("bootJar"))
    val sourcePaths = file(project(":test-mcp-server").layout.buildDirectory).resolve("libs/test-mcp-server-1.0.0.jar")
    from(sourcePaths)
    into(file(projDir).resolve("ctx_bin"))
    // Optionally rename it to a fixed name
    rename { "test-mcp-server.jar" }
}

tasks.register<Copy>("copyCommitDiffCtxMcp") {
    doFirst {
        delete(file(projDir).resolve("ctx_bin/commit-diff-context-mcp.jar"))
    }

    dependsOn(project(":commit-diff-context-mcp").tasks.named("bootJar"))
    val sourcePaths = file(project(":commit-diff-context-mcp").layout.buildDirectory).resolve("libs/commit-diff-context-mcp.jar")
    from(sourcePaths)
    into(file(projDir).resolve("ctx_bin"))
    // Optionally rename it to a fixed name
    rename { "commit-diff-context-mcp.jar" }
}

tasks.compileJava {
    dependsOn("copyTestMcpServer", "copyCommitDiffCtxMcp")
    finalizedBy("processMcpServerJson")
}

tasks.test {
    dependsOn("copyTestMcpServer", "processMcpServerJson")
}

tasks.bootJar {
    dependsOn(project(":function-calling").tasks.named("copyJar"))
}

tasks.register("processMcpServerJson") {
    group = "custom"
    description = "Processes server.json and replaces environment variables"
    dependsOn("processResources", "processTestResources")

    fun doFile(value: String, buildDir: String) {
        file(layout.projectDirectory).resolve(value)
            .listFiles { it -> it.name.endsWith(".json") }
            ?.forEach {
                println("Processing ${it.name}")
                val inputPath = it
                val outputPath = file(layout.buildDirectory).resolve("${buildDir}/${inputPath.name}")

                if (!inputPath.exists()) {
                    throw GradleException("File not found: ${inputPath.absolutePath}")
                }

                var originalContent = inputPath.readText()
                originalContent = originalContent.replace("{{PROJ_DIR}}", layout.projectDirectory.toString())

                outputPath.parentFile.mkdirs()
                outputPath.writeText(originalContent)
                println("Processed file written to: ${outputPath.absolutePath}")

            }
    }
    doLast {
        doFile("src/main/resources/mcp-servers", "resources/main/mcp-servers")
        doFile("src/test/resources/test-mcp-servers", "resources/test/test-mcp-servers")
    }
}

