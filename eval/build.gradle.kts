dependencies {
    implementation(project(":core"))
    implementation(project(":world"))
    implementation("tools.jackson.core:jackson-databind")
}

tasks.register<JavaExec>("evaluate") {
    description = "Runs every design on the development months and writes reports/dev.json."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mspagent.eval.Evaluate")
    args("dev")
    workingDir = rootDir
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("heldout") {
    description = "The held-out run, once, against the pinned runtime."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mspagent.eval.Evaluate")
    args("heldout")
    workingDir = rootDir
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("lock") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mspagent.eval.Evaluate")
    args("lock")
    workingDir = rootDir
}

tasks.register<JavaExec>("demo") {
    description = "Writes the console's demo data from a simulated week."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mspagent.eval.Evaluate")
    args("demo")
    workingDir = rootDir
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("readme") {
    description = "Writes the README's results from reports/."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("mspagent.eval.Evaluate")
    args("readme")
    workingDir = rootDir
}

dependencies {
    testImplementation("tools.jackson.core:jackson-databind")
}
