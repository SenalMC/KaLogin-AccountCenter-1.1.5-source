plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "8.3.0"
}

group = "top.cnuo"
version = "1.1.5"


dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("org.mindrot:jbcrypt:0.4")
    // Jakarta Mail API + Angus Mail SMTP provider.
    // Do not replace this with API-only dependencies, or smtp provider discovery will fail.
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api:2.1.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.mindrot", "top.cnuo.kalogin.accountcenter.libs.jbcrypt")
    relocate("jakarta.mail", "top.cnuo.kalogin.accountcenter.libs.jakarta.mail")
    relocate("jakarta.activation", "top.cnuo.kalogin.accountcenter.libs.jakarta.activation")
    relocate("org.eclipse.angus", "top.cnuo.kalogin.accountcenter.libs.angus")

    // Required for Jakarta Mail provider discovery.
    // Without this, Transport.send(...) may throw NoSuchProviderException: smtp.
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
