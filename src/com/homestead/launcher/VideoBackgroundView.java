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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;

/**
 * Hardware-decoded looping MP4 background player using two stacked TextureViews to
 * crossfade smoothly across the loop boundary so there is no visible jump or cut.
 */
final class VideoBackgroundView extends FrameLayout {

    private static final String TAG = "Homestead";
    private static final int FADE_MS = 1400;
    private static final int POLL_MS = 200;

    private final Slot[] slots = new Slot[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int active = 0;
    private File currentFile;
    private float playbackSpeed = 1.0f;
    private boolean soundEnabled = true;
    private boolean crossfading;

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!isShownNow() || currentFile == null) return;
            Slot cur = slots[active];
            Slot next = slots[1 - active];
            if (cur.mp != null && cur.prepared && !crossfading && next.prepared) {
                try {
                    int dur = cur.mp.getDuration();
                    int pos = cur.mp.getCurrentPosition();
                    if (dur > FADE_MS * 2 + 500 && pos >= dur - FADE_MS - 150) {
                        beginCrossfade();
                    }
                } catch (Throwable ignored) { }
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final class Slot implements TextureView.SurfaceTextureListener {
        final TextureView view;
        MediaPlayer mp;
        Surface surface;
        boolean prepared;
        int vw, vh, viewW, viewH;

        Slot(Context c) {
            view = new TextureView(c);
            view.setOpaque(true);
            view.setSurfaceTextureListener(this);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            viewW = width;
            viewH = height;
            if (surface != null) surface.release();
            surface = new Surface(st);
            if (currentFile != null) prepareSlot(this, this == slots[active]);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            viewW = width;
            viewH = height;
            applyCrop();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            release();
            if (surface != null) {
                surface.release();
                surface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) { }

        void applyCrop() {
            if (viewW <= 0 || viewH <= 0 || vw <= 0 || vh <= 0) return;
            float viewRatio = viewW / (float) viewH;
            float videoRatio = vw / (float) vh;
            float sx = 1f, sy = 1f;
            if (videoRatio > viewRatio) sx = videoRatio / viewRatio;
            else sy = viewRatio / videoRatio;
            Matrix m = new Matrix();
            m.setScale(sx, sy, viewW * 0.5f, viewH * 0.5f);
            view.setTransform(m);
        }

        void release() {
            prepared = false;
            view.animate().cancel();
            if (mp != null) {
                try { mp.stop(); } catch (Throwable ignored) { }
                try { mp.release(); } catch (Throwable ignored) { }
                mp = null;
            }
        }
    }

    VideoBackgroundView(Context c) {
        super(c);
        slots[0] = new Slot(c);
        slots[1] = new Slot(c);
        addView(slots[1].view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(slots[0].view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        slots[0].view.setAlpha(1f);
        slots[1].view.setAlpha(0f);
    }

    void setVideoFile(File file, float speed, boolean sound) {
        if (file == null) return;
        boolean sameFile = currentFile != null && currentFile.equals(file);
        boolean sameSpd = Math.abs(playbackSpeed - speed) < 0.01f;
        soundEnabled = sound;
        float vol = sound ? 0.35f : 0f;
        for (int i = 0; i < 2; i++) {
            if (slots[i].mp != null) {
                try { slots[i].mp.setVolume(vol, vol); } catch (Throwable ignored) { }
            }
        }
        if (sameFile && sameSpd && slots[active].mp != null) {
            syncPause();
            return;
        }
        currentFile = file;
        playbackSpeed = speed;
        crossfading = false;
        active = 0;
        slots[0].view.setAlpha(1f);
        slots[1].view.setAlpha(0f);
        slots[0].view.bringToFront();
        if (slots[0].surface != null) prepareSlot(slots[0], true);
        if (slots[1].surface != null) prepareSlot(slots[1], false);
    }

    void releasePlayer() {
        handler.removeCallbacksAndMessages(null);
        crossfading = false;
        slots[0].release();
        slots[1].release();
    }

    private boolean isShownNow() {
        return getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncPause();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        syncPause();
    }

    private void syncPause() {
        Slot cur = slots[active];
        if (isShownNow()) {
            if (cur.mp != null && cur.prepared && !cur.mp.isPlaying()) {
                try { cur.mp.start(); } catch (Throwable ignored) { }
            }
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_MS);
        } else {
            handler.removeCallbacks(poll);
            for (int i = 0; i < 2; i++) {
                if (slots[i].mp != null && slots[i].prepared) {
                    try { if (slots[i].mp.isPlaying()) slots[i].mp.pause(); } catch (Throwable ignored) { }
                }
            }
        }
    }

    private void prepareSlot(final Slot s, final boolean startImmediately) {
        s.release();
        if (currentFile == null || s.surface == null || !currentFile.isFile()) return;
        try {
            final MediaPlayer mp = new MediaPlayer();
            s.mp = mp;
            mp.setDataSource(currentFile.getAbsolutePath());
            mp.setSurface(s.surface);
            mp.setLooping(true);
            float vol = soundEnabled ? 0.35f : 0f;
            mp.setVolume(vol, vol);
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    s.vw = w;
                    s.vh = h;
                    s.applyCrop();
                }
            });
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    if (s.mp != m) return;
                    s.prepared = true;
                    s.applyCrop();
                    if (startImmediately && isShownNow()) {
                        applySpeed(m);
                        m.start();
                        handler.removeCallbacks(poll);
                        handler.postDelayed(poll, POLL_MS);
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "Video slot error " + what + "/" + extra);
                    s.prepared = false;
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to prepare video slot: " + t);
            s.release();
        }
    }

    private void beginCrossfade() {
        final Slot cur = slots[active];
        final Slot next = slots[1 - active];
        if (next.mp == null || !next.prepared) return;
        crossfading = true;
        try {
            next.mp.seekTo(0);
            applySpeed(next.mp);
            next.mp.start();
        } catch (Throwable t) {
            crossfading = false;
            return;
        }
        next.view.setAlpha(0f);
        next.view.bringToFront();
        next.view.animate().alpha(1f).setDuration(FADE_MS).start();
        handler.postDelayed(new Runnable() {
            public void run() {
                if (!crossfading) return;
                try {
                    if (cur.mp != null && cur.prepared) {
                        if (cur.mp.isPlaying()) cur.mp.pause();
                        cur.mp.seekTo(0);
                    }
                } catch (Throwable ignored) { }
                cur.view.setAlpha(0f);
                active = 1 - active;
                crossfading = false;
            }
        }, FADE_MS + 60);
    }

    private void applySpeed(MediaPlayer mp) {
        if (Build.VERSION.SDK_INT >= 23 && Math.abs(playbackSpeed - 1.0f) > 0.01f) {
            try {
                PlaybackParams pp = mp.getPlaybackParams();
                pp.setSpeed(playbackSpeed);
                mp.setPlaybackParams(pp);
            } catch (Throwable ignored) { }
        }
    }
}
