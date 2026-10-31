plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jsoup:jsoup:1.17.2")
}

// The end-to-end suite starts local fixture sites and drives the engine over real HTTP:  ./gradlew :engine:engineE2E
tasks.register<JavaExec>("engineE2E") {
    group = "verification"
    description = "Runs the engine end-to-end tests against local fixture servers."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.openmanga.engine.EngineTestsKt")
}
