plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation(libs.taoguan.luaj) { exclude(group = "org.apache.bcel", module = "bcel") }
    testImplementation(libs.junit)
}
tasks.test {
    systemProperty("watch.sample", providers.gradleProperty("watchSamplePath").orElse("").get())
}
