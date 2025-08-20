plugins {
    id("com.hayden.mcp")
    id("com.hayden.spring-app")
    id("com.hayden.observable-app")
    id("com.hayden.dgs-graphql")
    id("com.hayden.log")
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

tasks.register<Copy>("copyTestMcpServer") {
    dependsOn(project(":test-mcp-server").tasks.named("bootJar"))
    val sourcePaths = file(project(":test-mcp-server").layout.buildDirectory).resolve("libs/test-mcp-server-1.0.0.jar")
    from(sourcePaths)
    into(file(layout.buildDirectory).resolve("libs"))
    // Optionally rename it to a fixed name
    rename { "test-mcp-server.jar" }
}

tasks.test {
    dependsOn("copyTestMcpServer")
}
