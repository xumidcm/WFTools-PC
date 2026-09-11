plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":watch-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation("uk.co.caprica:vlcj:4.12.1")
    testImplementation(libs.junit)
}
