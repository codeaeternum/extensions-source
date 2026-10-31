import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NovelCool"
    versionCode = 12
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    val subdomains = mapOf(
        "en" to "www",
        "es" to "es",
        "de" to "de",
        "ru" to "ru",
        "it" to "it",
        "pt-BR" to "br",
        "fr" to "fr",
    )
    subdomains.forEach { (langCode, sub) ->
        source {
            lang = langCode
            baseUrl = "https://$sub.novelcool.com"
        }
    }

    // no host(): one intent filter is generated covering every source baseUrl
    deeplink {
        path("/novel/..*")
        path("/chapter/..*/..*")
    }
}

// ProGuard (createReleaseExtensionJar) rompe con rutas con espacios
// ("Devin - Sesiones Individuales"): se mueve buildDir fuera del checkout.
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.aeternum-ext-build/${project.path.replace(':', '_')}"))
