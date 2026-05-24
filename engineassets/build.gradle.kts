plugins {
    alias(libs.plugins.android.asset.pack)
}

/**
 * Holds the NNUE network files (~108MB) and the Vosk small English model directory (~68MB).
 *
 * deliveryType = "install-time" means the pack is downloaded by Play Store as part of
 * installation and is available immediately the first time the app runs — same UX as
 * bundled assets, but it doesn't count against the 200MB base APK size limit. Files are
 * accessed at runtime through the standard AssetManager API; no code changes vs. having
 * the files in app/src/main/assets.
 */
assetPack {
    packName.set("engineassets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
