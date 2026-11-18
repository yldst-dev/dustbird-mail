import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val greenmail: Configuration by configurations.creating

dependencies {
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.jsoup)
    api(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    greenmail(libs.greenmail)
}

val fixturesDir = rootProject.layout.projectDirectory.dir("testfixtures").asFile
val base64Mitigation = "-XX:-UseBASE64Intrinsics"

tasks.test {
    useJUnit()
    jvmArgs(base64Mitigation)
    systemProperty("greenmail.classpath", greenmail.asPath)
    systemProperty("fixtures.dir", fixturesDir.absolutePath)
    systemProperty("javax.net.ssl.trustStore", File(fixturesDir, "test-truststore.p12").absolutePath)
    systemProperty("javax.net.ssl.trustStoreType", "PKCS12")
    systemProperty("javax.net.ssl.trustStorePassword", "changeit")
}

tasks.register<JavaExec>("runTestServer") {
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("app.dustbird.mail.core.testing.TestServerMainKt")
    jvmArgs(base64Mitigation)
    systemProperty("greenmail.classpath", greenmail.asPath)
    systemProperty("fixtures.dir", rootProject.layout.projectDirectory.dir("testfixtures").asFile.absolutePath)
}
