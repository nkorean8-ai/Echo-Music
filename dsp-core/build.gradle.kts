plugins {
  id("org.jetbrains.kotlin.jvm")
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
  testImplementation(libs.junit)
}
