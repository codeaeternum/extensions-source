import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Rncalation"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://rncalation.online"
    }

    deeplink {
        path("/comics/..*")
        path("/leer/..*")
    }
}

// ProGuard (createReleaseExtensionJar) rompe con rutas con espacios
// ("Devin - Sesiones Individuales"): se mueve buildDir fuera del checkout.
layout.buildDirectory.set(File(System.getProperty("user.home"), ".aeternum-ext-build/${project.path.replace(':', '_')}"))
