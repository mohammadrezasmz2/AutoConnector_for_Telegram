package io.autoconnector.engine.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the relay automatically when the phone finishes booting (and after
 * an app upgrade), so the user doesn't have to open AutoConnector for Telegram
 * by hand for the relay to come up.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            // Opt-in only: the user enables "Auto-start when Telegram needs it" in
            // Settings. When off, we never start on boot — the user opens the app
            // themselves. When on, the relay comes up automatically so opening
            // Telegram just works, using the proxies saved from last time.
            try {
                if (!new io.autoconnector.engine.core.Prefs(ctx).autoStartOnTelegram()) return;
            } catch (Throwable ignored) {
                return;
            }
            try {
                RelayService.start(ctx);
            } catch (Exception ignored) {
                // Boot-time start may be denied on locked devices; the user's
                // first manual launch of the app starts it anyway.
            }
        }
    }
}
