/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;

/**
 * Obtains the one-time VPN consent (via VpnService.prepare()) and starts TunnelVpnService.
 * Draws nothing itself; only the system consent dialog may appear.
 *
 * Not exported: only Setup in this app may start the tunnel, since it makes adbd reachable.
 */
public class VpnSetupActivity extends Activity {

    private static final String TAG = "Homestead";
    private static final int REQ_CONSENT = 41;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent consent = VpnService.prepare(this);
            if (consent != null) {
                startActivityForResult(consent, REQ_CONSENT);
            } else {
                onActivityResult(REQ_CONSENT, RESULT_OK, null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "VPN prepare failed: " + t);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_CONSENT && res == RESULT_OK) {
            startService(new Intent(this, TunnelVpnService.class));
            Log.i(TAG, "VPN consent granted; tunnel starting");
        } else {
            Log.w(TAG, "VPN consent not granted (res=" + res + ")");
        }
        finish();
    }
}
