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

import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared singleton looping ambient MP3 player for animated/weather backgrounds.
 * Shared across MainActivity and HomeRedirectService so switching backgrounds or
 * hiding the launcher always updates/pauses the single active audio stream.
 */
final class AmbientAudioPlayer {

    private static final String TAG = "Homestead";
    private static final AmbientAudioPlayer INSTANCE = new AmbientAudioPlayer();

    private final Set<Object> visibleHosts = new HashSet<Object>();
    private MediaPlayer mp;
    private File current;
    private boolean prepared;

    private AmbientAudioPlayer() { }

    static AmbientAudioPlayer get() { return INSTANCE; }

    void setFile(File file) {
        if (file == null) {
            stop();
            return;
        }
        if (current != null && current.equals(file) && mp != null) {
            sync();
            return;
        }
        stop();
        current = file;
        if (!file.isFile()) return;
        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setDataSource(file.getAbsolutePath());
            p.setLooping(true);
            p.setVolume(0.30f, 0.30f);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer m) {
                    if (mp != m) return;
                    prepared = true;
                    if (!visibleHosts.isEmpty()) m.start();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer m, int what, int extra) {
                    Log.w(TAG, "Ambient audio error " + what + "/" + extra);
                    return true;
                }
            });
            p.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start ambient audio: " + t);
            stop();
        }
    }

    void setHostVisible(Object host, boolean v) {
        if (v) visibleHosts.add(host);
        else visibleHosts.remove(host);
        sync();
    }

    private void sync() {
        if (mp == null || !prepared) return;
        try {
            if (!visibleHosts.isEmpty()) {
                if (!mp.isPlaying()) mp.start();
            } else {
                if (mp.isPlaying()) mp.pause();
            }
        } catch (Throwable ignored) { }
    }

    void stop() {
        prepared = false;
        current = null;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) { }
            try { mp.release(); } catch (Throwable ignored) { }
            mp = null;
        }
    }
}
