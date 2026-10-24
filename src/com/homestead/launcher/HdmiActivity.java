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
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    /** How loud the input stays while another sound, such as Alexa, briefly plays over it. */
    private static final float DUCKED_VOLUME = 0.3f;

    private TvView tvView;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean settled = false;
    private AudioManager audio;
    /** An AudioFocusRequest on Android 8 and later; held as Object so older versions load the class. */
    private Object focusRequest;

    /**
     * The input is the media playing, so it holds audio focus like any player: other apps' sound
     * stops, and a short sound over it lowers it instead. Losing focus does not silence it, since
     * an input cannot be paused from here and the user is watching it.
     */
    private final AudioManager.OnAudioFocusChangeListener focusChange =
            new AudioManager.OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int change) {
            if (tvView == null) return;
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                tvView.setStreamVolume(1.0f);
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                tvView.setStreamVolume(DUCKED_VOLUME);
            }
        }
    };

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
        Lang.apply(this);

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
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);

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
                keepAwake(true);
            }
            @Override public void onVideoUnavailable(String id, int reason) {
                Log.w(TAG, "onVideoUnavailable " + id + " reason=" + reason);
                keepAwake(false);
                // reason 1 == TUNING, keep waiting; anything else means a dead port.
                if (reason != 1) {
                    settled = true;
                    handler.removeCallbacks(timeout);
                    status.setVisibility(View.VISIBLE);
                    status.setText(R.string.hdmi_no_signal);
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
        status.setText(getString(R.string.hdmi_connecting, label == null ? getString(R.string.home_input) : label));

        // Without a volume the TV's input service (MediaTek's, on Fire OS 7) plays the input at
        // volume 0: TvView only sends one once it has been set.
        tvView.setStreamVolume(1.0f);
        requestAudioFocus();
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
        // Amazon's Inputs screen lives in its launcher; with that switched off there is
        // nowhere to hand over to, so say so instead of closing on a blank screen.
        if (!AppModel.amazonLauncherOn(this)) {
            Log.w(TAG, "fallback: Amazon's launcher is off, no Inputs screen");
            if (status != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.hdmi_open_failed);
            }
            handler.postDelayed(new Runnable() {
                public void run() { finish(); }
            }, 4000L);
            return;
        }
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
        abandonAudioFocus();
        keepAwake(false);
    }

    /**
     * While the input shows a picture, keep the screen on: a game controller or a disc
     * player's remote does not count as activity, so the screensaver would start mid-game.
     * Without a picture ("No signal") the TV sleeps as usual.
     */
    private void keepAwake(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void requestAudioFocus() {
        if (audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build())
                            // The system lowers only sound that apps play themselves, and the
                            // input's sound goes straight to the speakers. This asks for the
                            // "lower your volume" call instead; nothing is paused.
                            .setWillPauseWhenDucked(true)
                            .setOnAudioFocusChangeListener(focusChange)
                            .build();
                }
                audio.requestAudioFocus((AudioFocusRequest) focusRequest);
            } else {
                audio.requestAudioFocus(focusChange, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Throwable t) {
            Log.w(TAG, "audio focus request failed: " + t);
        }
    }

    private void abandonAudioFocus() {
        if (audio == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest != null) audio.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
            } else {
                audio.abandonAudioFocus(focusChange);
            }
        } catch (Throwable t) { }
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
