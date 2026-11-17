plugins {
    id("org.springframework.boot") version "4.0.9" apply false
}

val bootVersion = "4.0.9"

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
        if (project.name != "service") {
            options.compilerArgs.add("-Werror")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        workingDir = rootDir
        maxHeapSize = "2g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Resolves every dependency so the build can run offline (used once to seed a local cache).
    tasks.register("resolveAll") {
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}
