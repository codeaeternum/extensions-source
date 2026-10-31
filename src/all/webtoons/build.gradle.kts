import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Webtoons.com"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    listOf("en", "id", "th", "es", "fr", "zh-Hant", "de").forEach { langCode ->
        source {
            lang = langCode
            baseUrl = "https://www.webtoons.com"
            when (langCode) {
                // ID was removed as part of the name to be more consistent with other entries
                "id" -> id = 8749627068478740298

                // ID kept due to lang code getting more specific
                "zh-Hant" -> id = 2959982438613576472
            }
        }
    }

    deeplink {
        host("webtoons.com")
        host("www.webtoons.com")
        host("m.webtoons.com")
        path("/.*/.*/.*/..*")
        path("/.*/.*/.*/.*/..*")
    }
}

// ProGuard (createReleaseExtensionJar) breaks on checkout paths containing spaces.
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.aeternum-ext-build/${project.path.replace(':', '_')}"))
