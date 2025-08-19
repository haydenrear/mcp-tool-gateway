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
}

tasks.generateJava {
    typeMapping = mutableMapOf(
        Pair("ServerByteArray", "com.hayden.commitdiffmodel.scalar.ByteArray"),
        Pair("Float32Array", "com.hayden.commitdiffmodel.scalar.FloatArray"),
    )
}
