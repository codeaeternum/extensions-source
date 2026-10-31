import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "LectorTMOo"
    versionCode = 16
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "LectorTMOo"
        baseUrl = "https://lectortmo.online"
        lang = "es"
    }
    source {
        name = "TuMangaHentai"
        baseUrl = "https://tumangahentai.com"
        lang = "es"
    }

    deeplink {
        host("lectortmo.online")
        host("www.lectortmo.online")
        host("tumangahentai.com")
        host("www.tumangahentai.com")
        path("/manga/..*")
        path("/..*-capitulo-..*")
    }
}

// ProGuard (createReleaseExtensionJar) rompe con rutas con espacios
// ("Devin - Sesiones Individuales"): se mueve buildDir fuera del checkout.
layout.buildDirectory.set(File(System.getProperty("user.home"), ".aeternum-ext-build/${project.path.replace(':', '_')}"))
