package com.englishai.app

import androidx.browser.customtabs.CustomTabsCallback
import com.google.androidbrowserhelper.trusted.LauncherActivity

/**
 * Avoids Android Browser Helper's default QualityEnforcer intentionally crashing the
 * host app when the browser reports a TWA origin-verification quality-enforcement event.
 * The PWA remains available in the browser's fallback/custom-tab mode until Digital Asset
 * Links is configured with the release signing certificate.
 */
class EnglishAiLauncherActivity : LauncherActivity() {
    override fun getCustomTabsCallback(): CustomTabsCallback = CustomTabsCallback()
}
