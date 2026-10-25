// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

layout.buildDirectory.set(file(System.getProperty("java.io.tmpdir") + "/reellimit-build/root"))
