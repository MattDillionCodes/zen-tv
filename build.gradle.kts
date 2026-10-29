plugins {
    id("com.android.application") version "8.12.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Building inside an iCloud-synced folder? iCloud copies files while Gradle writes them ("banner 2.xml")
// and breaks the build, but it skips anything named *.nosync. Opt in by adding this to local.properties:
//   zen.nosyncBuildDir=true
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
if (localProps.getProperty("zen.nosyncBuildDir") == "true") {
    allprojects { layout.buildDirectory.set(file("build.nosync")) }
}
