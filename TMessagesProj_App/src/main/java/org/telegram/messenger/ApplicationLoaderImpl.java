package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

/**
 * CQ sideload build (package {@code je.cq.tg}).
 * Marked standalone so in-app update / direct-APK paths match me.cq.je distribution.
 */
public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isStandalone() {
        return true;
    }
}
