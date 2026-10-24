package com.englishai.app;

import androidx.browser.customtabs.CustomTabsCallback;
import com.google.androidbrowserhelper.trusted.LauncherActivity;

/**
 * Prevents the default TWA quality-enforcement callback from deliberately
 * terminating the launcher when origin verification is unavailable.
 */
public class EnglishAiLauncherActivity extends LauncherActivity {
    @Override
    protected CustomTabsCallback getCustomTabsCallback() {
        return new CustomTabsCallback();
    }
}
