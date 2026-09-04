plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "bot.kurtz"
version = "1.0-SNAPSHOT"

val ktorVersion = project.property("ktorVersion") as String
val exposedVersion = project.property("exposedVersion") as String
val coroutinesVersion = "1.11.0"
val log4jVersion = "2.26.1"

repositories {
    mavenCentral()
}

dependencies {
    // Terminal UI
    implementation("com.varabyte.kotter:kotter-jvm:1.4.0")

    // HTTP + JSON
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // The simulator: virtual time for the `sim` command and a fake server behind the real client
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    implementation("io.ktor:ktor-client-mock:$ktorVersion")

    // Exposed libs for Sqlite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")

    // SQLite driver for Exposed
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.3")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:$log4jVersion")
    implementation("org.apache.logging.log4j:log4j-core:$log4jVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
    // A test that spins on the virtual clock must fail, not stall the build.
    systemProperty("junit.jupiter.execution.timeout.default", "3m")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}
