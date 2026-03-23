plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
//    alias(libs.plugins.ksp) apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}