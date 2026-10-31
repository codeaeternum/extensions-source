import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "ManhwaWeb"
    versionCode = 26
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "es"
        baseUrl = "https://manhwaweb.com"
    }

    deeplink {
        path("/manhwa/..*")
        path("/manga/..*")
        path("/leer/..*")
    }
}

// ProGuard (createReleaseExtensionJar) fails on paths with spaces
// ("Devin - Sesiones Individuales"), so build outside the checkout.
layout.buildDirectory.set(file(System.getProperty("user.home") + "/.aeternum-ext-build/${project.path.replace(':', '_')}"))
