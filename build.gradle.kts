plugins {
    java
}

group = "de.raritysystem"
version = "1.0.0"

// !!! An deine Paper-Version anpassen (siehe https://docs.papermc.io -> Project Setup) !!!
val paperVersion = "26.3.build.+"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
