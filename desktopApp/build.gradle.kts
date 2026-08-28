import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }

    dependencies {
        implementation(project(":composeApp"))

        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutinesSwing)
        implementation(libs.compose.uiToolingPreview)
        implementation(libs.koin.core)
        implementation(libs.koin.compose)

        implementation(libs.fileKit)
    }
}

compose.desktop {
    application {
        mainClass = "com.ninetag.machum.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.ninetag.machum"
            packageVersion = "1.0.0"
        }
    }
}
