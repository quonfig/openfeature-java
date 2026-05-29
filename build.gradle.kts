import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api("dev.openfeature:sdk:1.20.2")
    // api scope: com.quonfig.sdk.Quonfig is on the public surface (QuonfigProvider
    // constructor + getClient()), so consumers need it at compile time. See qfg-jcgj.
    api("com.quonfig:sdk-java:0.0.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Mirrors sdk-java's publish setup: Sonatype Central Portal + in-memory GPG
// signing, sources + javadoc jars. Single-module here, so the vanniktech
// plugin is applied directly to the root project rather than to subprojects.
extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
}
