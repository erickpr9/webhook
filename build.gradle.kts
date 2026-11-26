plugins {
    java
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "com.estudos"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    buildInfo()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.springdoc.openapi)
    implementation(libs.logstash.logback.encoder)

    runtimeOnly("org.postgresql:postgresql")
    // Sem o registry o /actuator/prometheus responde 404.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.awaitility:awaitility")
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

// ── Windows: pasta dos sockets locais do NIO ────────────────────────────────
// No Windows, o JDK cria um socket AF_UNIX na pasta temporária do usuário para o Selector do NIO
// (usado pelo Tomcat e pelo HttpClient). Quando essa pasta fica dentro de um perfil com espaço
// no nome (ex.: C:\Users\Nome Sobrenome, acessado como C:\Users\NOMESO~1), a conexão falha com
// "Unable to establish loopback connection". Usa uma pasta curta dentro de build/ para isso.
val isWindows = System.getProperty("os.name").startsWith("Windows")
val unixDomainTmpDir = layout.buildDirectory.dir("tmp/uds")

fun JavaForkOptions.useShortUnixDomainTmpDir(task: Task) {
    val dir = unixDomainTmpDir.get().asFile
    jvmArgs("-Djdk.net.unixdomain.tmpdir=${dir.absolutePath}")
    task.doFirst { dir.mkdirs() }
}

if (isWindows) {
    // JavaExec cobre o bootRun; Test cobre os testes.
    tasks.withType<JavaExec>().configureEach { useShortUnixDomainTmpDir(this) }
    tasks.withType<Test>().configureEach { useShortUnixDomainTmpDir(this) }
}

// ── Testes ──────────────────────────────────────────────────────────────────
tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    // Repassa a configuração do Docker para o Testcontainers quando definida no ambiente.
    listOf("DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED").forEach { key ->
        System.getenv(key)?.let { environment(key, it) }
    }
    System.getenv("DOCKER_API_VERSION")?.let { systemProperty("api.version", it) }
}

// ── Cobertura (JaCoCo) ──────────────────────────────────────────────────────
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val coverageExclusions = listOf(
    "**/WebhookApplication*",
    "**/config/**",
    "**/dev/**",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } })
    )
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
