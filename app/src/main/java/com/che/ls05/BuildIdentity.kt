package com.che.ls05

/** Kimlik, APK'nın derlendiği commit ve CI denemesine kadar izlenebilir. */
object BuildIdentity {
    val label: String = "Finn's Watch ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER} · " +
        "run ${BuildConfig.BUILD_RUN} · ${BuildConfig.BUILD_COMMIT} · ${BuildConfig.BUILD_BRANCH}"
}
