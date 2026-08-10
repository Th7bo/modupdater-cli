plugins {
    java
    application
}

group = "dev.th7bo.modupdater"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // The JDK has no JSON parser and the manifest is a document we don't control,
    // so parsing it by hand is not worth the risk. Gson is the only runtime dep.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.th7bo.modupdater.Main"
}

tasks.test {
    useJUnitPlatform()
    // Without this a machine with a display would open a real modal dialog during
    // the test run and hang it forever.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed")
    }
}

// What you actually hand to someone: the JAR, the hook scripts, the installer,
// and the README, in one zip. The JAR is renamed to the stable name the scripts
// look for, so the version never has to be edited into them.
val installerZip = tasks.register<Zip>("installerZip") {
    group = "distribution"
    description = "Builds the zip to hand to users: jar + hook scripts + installer."

    archiveFileName = "modupdater-installer.zip"
    destinationDirectory = layout.buildDirectory.dir("dist")

    from(tasks.jar) {
        rename { "modupdater-cli.jar" }
    }
    from(layout.projectDirectory.dir("scripts"))
    from(layout.projectDirectory.file("README.md"))

    // The shell scripts must stay executable after unzipping on Linux/macOS.
    filesMatching(listOf("*.sh")) {
        permissions { unix("rwxr-xr-x") }
    }
}

tasks.named("assemble") {
    dependsOn(installerZip)
}

// Single runnable JAR. Done with a plain Jar task rather than a shading plugin:
// there is exactly one dependency to bundle, and this avoids pinning a plugin
// version against Gradle's own release cycle.
tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
