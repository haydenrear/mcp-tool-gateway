plugins {
    id("com.hayden.mcp")
    id("com.hayden.spring-app")
    id("com.hayden.observable-app")
    id("com.hayden.dgs-graphql")
    id("com.hayden.log")
    id("com.hayden.docker-compose")
    id("com.hayden.paths")
    id("com.hayden.jpa-persistence")
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
    implementation(project(":jpa-persistence"))
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("io.fabric8:kubernetes-client:6.11.0")

    testImplementation("org.wiremock:wiremock-standalone:3.9.1")
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
    dependsOn("copyTestMcpServer", "copyCommitDiffCtxMcp", "processYmlFiles")
    finalizedBy("processMcpServerJson")
}

tasks.test {
    dependsOn("copyTestMcpServer", "processMcpServerJson", "processYmlFiles")
}


//TODO: for docker
tasks.bootJar {
//    dependsOn(project(":function-calling").tasks.named("copyJar"))
    archiveFileName = "mcp-tool-gateway.jar"
}
