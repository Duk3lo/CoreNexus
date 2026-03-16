plugins {
    id("java")
    id("com.gradleup.shadow") version "9.2.0"
}

group = "org.astral.core"
version = "0.0.2"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jline:jline:3.25.1")
    implementation("org.jline:jline-terminal-jansi:3.25.1")
    implementation("org.kohsuke:github-api:1.327")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.shadowJar {
    archiveBaseName.set("CoreNexus")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Main-Class" to "org.astral.core.Main",
            "Enable-Native-Access" to "ALL-UNNAMED"
        )
    }
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
}