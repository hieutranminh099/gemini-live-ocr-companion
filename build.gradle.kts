plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}

subprojects {
    if (name == "app") {
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("app/build"))
    }
}
