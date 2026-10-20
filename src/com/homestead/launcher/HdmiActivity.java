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
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen viewer for a passthrough (HDMI / composite) input, hosted with TvView.
 *
 * Amazon's input switcher requires a signature-level permission, and a passthrough VIEW
 * intent only opens Amazon's Live TV settings, so the input is shown here directly. If
 * tuning fails, fall back to Amazon's Inputs picker.
 *
 * Tuning happens in onResume: the activity is singleTop, so a new input reuses the
 * instance after onPause has already reset the TvView.
 */
public class HdmiActivity extends Activity {

    private static final String TAG = "Homestead";
    public static final String EXTRA_INPUT_ID = "input_id";
    public static final String EXTRA_LABEL = "label";
    private static final long TUNE_TIMEOUT_MS = 6000L;

    private TvView tvView;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean settled = false;

    private final Runnable timeout = new Runnable() {
        public void run() {
            if (!settled) {
                Log.w(TAG, "tune timed out, falling back to Amazon's Inputs picker");
                fallback();
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        tvView = new TvView(this);
        root.addView(tvView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);

        tvView.setCallback(new TvView.TvInputCallback() {
            @Override public void onConnectionFailed(String id) {
                Log.w(TAG, "onConnectionFailed " + id);
                fallback();
            }
            @Override public void onDisconnected(String id) {
                Log.w(TAG, "onDisconnected " + id);
                fallback();
            }
            @Override public void onVideoAvailable(String id) {
                Log.i(TAG, "onVideoAvailable " + id);
                settled = true;
                handler.removeCallbacks(timeout);
                status.setVisibility(View.GONE);
            }
            @Override public void onVideoUnavailable(String id, int reason) {
                Log.w(TAG, "onVideoUnavailable " + id + " reason=" + reason);
                // reason 1 == TUNING, keep waiting; anything else means a dead port.
                if (reason != 1) {
                    settled = true;
                    handler.removeCallbacks(timeout);
                    status.setVisibility(View.VISIBLE);
                    status.setText("No signal on this input");
                }
            }
        });
    }

    /** singleTop: a different input arrives here, not through onCreate. */
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent in = getIntent();
        String inputId = (in == null) ? null : in.getStringExtra(EXTRA_INPUT_ID);
        String label = (in == null) ? null : in.getStringExtra(EXTRA_LABEL);
        if (inputId == null) { finish(); return; }

        settled = false;
        status.setVisibility(View.VISIBLE);
        status.setText((label == null ? "Input" : label) + " — connecting…");

        try {
            tvView.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId));
            handler.postDelayed(timeout, TUNE_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.w(TAG, "tune threw: " + t);
            fallback();
        }
    }

    /** Hand off to Amazon's Inputs screen, standing the Home redirect down first. */
    private void fallback() {
        if (isFinishing()) return;
        settled = true;
        handler.removeCallbacks(timeout);
        try {
            HomeRedirectService.suspend(15000L);
            Intent i = new Intent("com.amazon.tv.action.NAVIGATE_TO_INPUTS");
            // Pinned so no other app that declares this action can receive it.
            i.setPackage("com.amazon.tv.launcher");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "fallback failed: " + t);
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(timeout);
        try { if (tvView != null) tvView.reset(); } catch (Throwable t) { }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
