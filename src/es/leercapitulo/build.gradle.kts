import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LeerCapitulo"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://www.leercapitulo.co"
    }

    deeplink {
        path("/manga/..*")
        path("/leer/..*")
    }
}

// ProGuard (createReleaseExtensionJar) rompe con rutas con espacios
// ("Devin - Sesiones Individuales"): se mueve buildDir fuera del checkout.
layout.buildDirectory.set(File(System.getProperty("user.home"), ".aeternum-ext-build/${project.path.replace(':', '_')}"))
