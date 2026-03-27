pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "orka"

include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:designsystem",
    ":core:database",
    ":core:testing",
    ":feature:onboarding",
    ":feature:capture",
    ":feature:tasks",
    ":feature:taskdetail",
    ":feature:archive",
    ":feature:settings",
    ":feature:diagnostics",
    ":feature:alarm",
    ":data:parser",
    ":data:scheduler",
    ":data:behavior",
    ":data:execution",
    ":data:rl",
    ":benchmark",
    ":baselineprofile",
)
