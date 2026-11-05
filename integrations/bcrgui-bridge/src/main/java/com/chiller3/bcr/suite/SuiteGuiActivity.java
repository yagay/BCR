package com.chiller3.bcr.suite;

import android.os.Bundle;
import com.github.nicorac.bcrgui.MainActivity;

/**
 * The suite's only launcher. Inherits all original GUI native plugins,
 * and adds the local BCR Settings bridge before Capacitor is initialized.
 */
public final class SuiteGuiActivity extends MainActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BcrSuitePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
