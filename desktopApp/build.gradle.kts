import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":watch-core"))
    implementation(project(":watch-renderer"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation(libs.junit)
    testImplementation(compose.desktop.uiTestJUnit4)
}
compose.desktop {
    application {
        mainClass = "com.ailife.wftools.pc.MainKt"
        nativeDistributions {
            if (System.getProperty("os.name").lowercase().contains("linux")) targetFormats(TargetFormat.Deb)
            else targetFormats(TargetFormat.Msi)
            packageName = "WFTools-PC"
            packageVersion = "0.1.5"
            description = "WatchMaker desktop editor"
            vendor = "WFTools-PC"
            modules("java.desktop", "java.xml", "java.logging", "java.net.http", "jdk.unsupported")
            windows {
                iconFile.set(project.file("../packaging/WFTools-PC.ico"))
                perUserInstall = true
                menu = true
                shortcut = true
                upgradeUuid = "19a1ac0c-fdd0-4411-9dd5-a2b95f1ce108"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/wftools.png"))
            }
        }
    }
}
