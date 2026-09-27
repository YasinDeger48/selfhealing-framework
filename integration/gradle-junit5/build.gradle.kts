// A Gradle project using the framework like any user would. CI runs it against the freshly built version
// (mvn install puts it into the local Maven repository): gradle test -PhealerVersion=2.2.0
plugins {
    java
}

val healerVersion: String = (findProperty("healerVersion") as String?) ?: "2.2.0"

repositories {
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    testImplementation("io.github.yasindeger48:healer-playwright:$healerVersion")
    testImplementation("io.github.yasindeger48:healer-junit5:$healerVersion")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // browser.name etc. come from src/test/resources/healer.properties; -Pbrowser=chromium overrides it
    (findProperty("browser") as String?)?.let { systemProperty("browser.name", it) }
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
