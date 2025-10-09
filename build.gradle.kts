plugins {
    id("com.hayden.mcp")
    id("com.hayden.spring-app")
    id("com.hayden.observable-app")
    id("com.hayden.dgs-graphql")
    id("com.hayden.log")
    id("com.hayden.docker-compose")
    id("com.hayden.paths")
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

