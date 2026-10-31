import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "HentaiMode"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://hentaimode.com"
    }

    deeplink {
        host("hentaimode.com")
        path("/g/..*")
        path("/leer/..*")
    }
}

// ProGuard (createReleaseExtensionJar) rompe con rutas con espacios
// ("Devin - Sesiones Individuales"): se mueve buildDir fuera del checkout.
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.aeternum-ext-build/${project.path.replace(':', '_')}"))
