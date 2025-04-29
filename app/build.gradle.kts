plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
    application
}

repositories {
    mavenCentral()
    maven { url = uri("https://www.jitpack.io" ) }
}

dependencies {
    implementation(project(":utils"))

    implementation ("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.3.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.github.oshi:oshi-core:6.8.1")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass = "org.example.app.MainKt"
}

kotlin {
    jvmToolchain(21)
}
