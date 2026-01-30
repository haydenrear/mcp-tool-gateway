import Com_hayden_docker_gradle.DockerContext
import java.nio.file.Paths

plugins {
    id("com.hayden.mcp")
    id("com.hayden.spring-app")
    id("com.hayden.observable-app")
    id("com.hayden.graphql")
    id("com.hayden.log")
    id("com.hayden.docker-compose")
    id("com.hayden.paths")
    id("com.hayden.jpa-persistence")
    id("com.hayden.docker")
    id("com.hayden.wiremock")
}

group = "com.hayden"
version = "1.0.0"

tasks.register("prepareKotlinBuildScriptModel") {}

val registryBase = project.property("registryBase") ?: "localhost:5001"

val enableDocker = project.property("enable-docker")?.toString()?.toBoolean()?.or(false) ?: false
val buildMcpToolGateway = project.property("build-mcp-tool-gateway")?.toString()?.toBoolean()?.or(false) ?: false


var arrayOf = arrayOf(
    DockerContext(
        "${registryBase}/mcp-tool-gateway",
        "${project.projectDir}/src/main/docker",
        "mcpToolGateway"
    )
)

if (!enableDocker || !buildMcpToolGateway)
    arrayOf = emptyArray<DockerContext>()

wrapDocker {
    ctx = arrayOf
}

dependencies {
    implementation(project(":utilitymodule"))
    implementation(project(":graphql"))
    implementation(project(":commit-diff-model"))
    implementation(project(":commit-diff-context"))
    implementation(project(":tracing"))
    implementation(project(":test-mcp-server"))
    implementation(project(":jpa-persistence"))
    implementation(project(":persistence"))
    implementation(project(":runner_code"))
    implementation(project(":acp-cdc-ai"))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("io.fabric8:kubernetes-client:6.11.0")
}

val projDir = layout.projectDirectory

tasks.register<Copy>("copyTestMcpServer") {
    doFirst {
        delete(file(projDir).resolve("ctx_bin/test-mcp-server.jar"))
    }

    dependsOn(project(":test-mcp-server").tasks.named("bootJar"))
    val sourcePaths = file(project(":test-mcp-server").layout.buildDirectory).resolve("libs/test-mcp-server.jar")
    from(sourcePaths)
    into(file(projDir).resolve("ctx_bin"))
    // Optionally rename it to a fixed name
    rename { "test-mcp-server.jar" }
}

//tasks.register<Copy>("copyCommitDiffCtxMcp") {
//    doFirst {
//        delete(file(projDir).resolve("ctx_bin/commit-diff-context-mcp.jar"))
//    }
//
//    dependsOn(project(":commit-diff-context-mcp").tasks.named("bootJar"))
//    val sourcePaths = file(project(":commit-diff-context-mcp").layout.buildDirectory).resolve("libs/commit-diff-context-mcp.jar")
//    from(sourcePaths)
//    into(file(projDir).resolve("ctx_bin"))
//    // Optionally rename it to a fixed name
//    rename { "commit-diff-context-mcp.jar" }
//}


if (enableDocker && buildMcpToolGateway) {
    tasks.getByPath("bootJar").finalizedBy("buildDocker")

    tasks.getByPath("bootJar").doLast {
        tasks.getByPath("mcpToolGatewayDockerImage")
            .dependsOn(project(":runner_code").tasks.getByName("runnerTask"), "copyJar")
    }

    tasks.register("buildDocker") {
        dependsOn("bootJar", "copyJar", "mcpToolGatewayDockerImage")
        doLast {
            delete(fileTree(Paths.get(projectDir.path, "src/main/docker")) {
                include("**/*.jar")
            })
        }
    }

    tasks.register("mcpToolGatewayTask") {
        dependsOn("buildDocker")
    }
} else {
    tasks.register("mcpToolGatewayTask") {
    }
}

tasks.compileJava {
    dependsOn(
        "copyTestMcpServer",
//        "copyCommitDiffCtxMcp",
        "processYmlFiles")
    finalizedBy("processMcpServerJson")
}

tasks.test {
    dependsOn("processMcpServerJson", "processYmlFiles")
}


//TODO: for docker
tasks.bootJar {
//    dependsOn(project(":function-calling").tasks.named("copyJar"))
    archiveFileName = "mcp-tool-gateway.jar"
    mainClass = "com.hayden.mcptoolgateway.McpToolGatewayApplication"
    enabled = true
}
