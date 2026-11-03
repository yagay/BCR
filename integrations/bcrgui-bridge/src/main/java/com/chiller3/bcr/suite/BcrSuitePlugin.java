package com.chiller3.bcr.suite;

import android.content.Intent;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Suite-only local navigation. No methods to grant root, read call logs,
 * disable recording or alter permissions are exported to WebView code.
 */
@CapacitorPlugin(name = "BcrSuite")
public final class BcrSuitePlugin extends Plugin {
    @PluginMethod
    public void openBcrSettings(PluginCall call) {
        try {
            Intent intent = new Intent();
            intent.setClassName(getContext().getPackageName(),
                    "com.chiller3.bcr.settings.SettingsActivity");
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Cannot open BCR settings", e);
        }
    }
}
