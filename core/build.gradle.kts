plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("tools.jackson.core:jackson-databind")
    testFixturesImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.9"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
}
