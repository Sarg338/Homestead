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
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Draws the built-in landscape backgrounds, so they live in source rather than as binaries:
 * layered ridges, a lake, a misty forest and dunes, each 1920x1080. Fixed seeds, so every
 * build draws the same pictures.
 *
 * Run:  java tools/MakeLandscapes.java <outDir>  (flat: aapt2 on Windows stores subfolders with a backslash)
 */
public final class MakeLandscapes {

    static final int W = 1920, H = 1080;

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : "assets");
        out.mkdirs();
        save(dusk(), new File(out, "landscape_drawn_dusk.jpg"));
        save(lake(), new File(out, "landscape_drawn_lake.jpg"));
        save(forest(), new File(out, "landscape_drawn_forest.jpg"));
        save(dunes(), new File(out, "landscape_drawn_dunes.jpg"));
    }

    static void save(BufferedImage img, File f) throws Exception {
        if (!ImageIO.write(img, "jpg", f)) throw new IllegalStateException("no JPEG writer");
    }

    static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** A vertical gradient through the given stops, 0 at the top and 1 at y = h. */
    static void sky(Graphics2D g, int h, float[] at, Color[] cs) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, h, at, cs));
        g.fillRect(0, 0, W, h);
    }

    /**
     * A ridge line by midpoint displacement: heights for every x, around base, roughness
     * halving at each step so the far ranges stay soft and the near ones rugged.
     */
    static float[] ridge(Random r, float base, float amp, float rough) {
        int n = 1024;
        float[] p = new float[n + 1];
        p[0] = base + (r.nextFloat() - 0.5f) * amp;
        p[n] = base + (r.nextFloat() - 0.5f) * amp;
        for (int step = n; step > 1; step /= 2) {
            for (int i = 0; i < n; i += step) {
                int m = i + step / 2;
                p[m] = (p[i] + p[i + step]) / 2 + (r.nextFloat() - 0.5f) * amp;
            }
            amp *= rough;
        }
        float[] y = new float[W + 1];
        for (int x = 0; x <= W; x++) y[x] = p[x * n / W];
        return y;
    }

    static void fillBelow(Graphics2D g, float[] y, Paint paint) {
        Path2D.Float s = new Path2D.Float();
        s.moveTo(0, H);
        for (int x = 0; x <= W; x++) s.lineTo(x, y[x]);
        s.lineTo(W, H);
        s.closePath();
        g.setPaint(paint);
        g.fill(s);
    }

    /** Between two colours: t 0 gives a, 1 gives b. */
    static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    static void glow(Graphics2D g, float cx, float cy, float r, Color c) {
        g.setPaint(new RadialGradientPaint(cx, cy, r, new float[] { 0f, 1f },
                new Color[] { c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0) }));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
    }

    // ---------------------------------------------------------------- scenes

    /** Ranges fading into a violet and amber sky, the sun low between them. */
    static BufferedImage dusk() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        sky(g, H, new float[] { 0f, 0.45f, 0.75f, 1f }, new Color[] {
                new Color(0x1B1640), new Color(0x5B2A63), new Color(0xD8705A), new Color(0xF6B26B) });
        glow(g, W * 0.62f, H * 0.58f, 420, new Color(255, 214, 150, 170));
        g.setColor(new Color(0xFFE2B0));
        g.fill(new Ellipse2D.Float(W * 0.62f - 60, H * 0.58f - 60, 120, 120));
        Random r = new Random(11);
        Color far = new Color(0xB86A72), near = new Color(0x14102A);
        for (int i = 0; i < 5; i++) {
            float t = i / 4f;
            fillBelow(g, ridge(r, H * (0.52f + 0.11f * i), 260 - 30 * i, 0.55f), mix(far, near, t));
        }
        g.dispose();
        return img;
    }

    /** Snowy peaks over a still lake that mirrors them, dark pines along the shore. */
    static BufferedImage lake() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        int shore = (int) (H * 0.62f);
        sky(g, shore, new float[] { 0f, 1f }, new Color[] { new Color(0x1E3A5F), new Color(0x8FB3D1) });
        Random r = new Random(23);
        float[] peaks = ridge(r, H * 0.34f, 460, 0.6f);
        float[] range = ridge(r, H * 0.47f, 170, 0.58f);
        float[] hills = ridge(r, H * 0.56f, 90, 0.6f);
        // The scene above the shore, drawn twice: once, then flipped into the water.
        BufferedImage top = new BufferedImage(W, shore, BufferedImage.TYPE_INT_ARGB);
        Graphics2D t = canvas(top);
        // Snow on the summits only, blue rock below.
        fillBelow(t, peaks, new GradientPaint(0, H * 0.14f, new Color(0xF2F5F8), 0, H * 0.36f, new Color(0x4A6683)));
        fillBelow(t, range, new Color(0x34506B));
        fillBelow(t, hills, new Color(0x223B4F));
        trees(t, r, shore, 26, 70, 150, new Color(0x0F1F2A));
        t.dispose();
        g.drawImage(top, 0, 0, null);
        g.setPaint(new GradientPaint(0, shore, new Color(0x5E85A8), 0, H, new Color(0x10243A)));
        g.fillRect(0, shore, W, H - shore);
        Composite c = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        g.drawImage(top, 0, shore + shore, W, shore, 0, 0, W, shore, null);
        g.setComposite(c);
        // Faint ripples across the reflection.
        g.setColor(new Color(255, 255, 255, 10));
        for (int y = shore + 6; y < H; y += 9 + (y - shore) / 25) g.fillRect(0, y, W, 1);
        g.dispose();
        return img;
    }

    /** Rows of pines receding into a pale green mist. */
    static BufferedImage forest() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        sky(g, H, new float[] { 0f, 1f }, new Color[] { new Color(0xC9D6CF), new Color(0x8FA79A) });
        Random r = new Random(37);
        Color mist = new Color(0xB3C4BA), dark = new Color(0x0E1F17);
        for (int i = 0; i < 5; i++) {
            float t = i / 4f;
            int base = (int) (H * (0.55f + 0.11f * i));
            Color c = mix(mist, dark, t);
            fillBelow(g, ridge(r, base, 40, 0.6f), c);
            trees(g, r, base + 10, 34 - 4 * i, 90 + 60 * i, 200 + 110 * i, c);
        }
        g.dispose();
        return img;
    }

    /** Warm dunes under a clear evening sky, each lit on one side. */
    static BufferedImage dunes() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(img);
        sky(g, H, new float[] { 0f, 0.55f, 1f }, new Color[] {
                new Color(0x23395B), new Color(0xE3A86B), new Color(0xF2C98E) });
        glow(g, W * 0.25f, H * 0.5f, 380, new Color(255, 236, 200, 150));
        Color[] lit = { new Color(0xD9935A), new Color(0xC97A44), new Color(0xA65E33), new Color(0x7A4224) };
        for (int i = 0; i < lit.length; i++) {
            float base = H * (0.55f + 0.12f * i);
            float[] y = new float[W + 1];
            double ph = i * 1.7, f = 0.0021 + i * 0.0007;
            for (int x = 0; x <= W; x++) {
                y[x] = (float) (base - 70 * Math.sin(x * f + ph) - 30 * Math.sin(x * f * 2.3 + ph * 1.3));
            }
            fillBelow(g, y, new GradientPaint(0, base - 100, lit[i], 0, base + 160,
                    mix(lit[i], new Color(0x2A160C), 0.55f)));
        }
        g.dispose();
        return img;
    }

    /** Pine silhouettes standing on a line: stacked triangles, random height and spacing. */
    static void trees(Graphics2D g, Random r, int ground, int count, int minH, int maxH, Color c) {
        g.setColor(c);
        for (int i = 0; i < count; i++) {
            float x = r.nextFloat() * W;
            float h = minH + r.nextFloat() * (maxH - minH);
            float w = h * 0.32f;
            Path2D.Float t = new Path2D.Float();
            for (int k = 0; k < 4; k++) {
                float top = ground - h + k * h * 0.2f;
                float half = w * (0.35f + 0.22f * k);
                t.moveTo(x, top);
                t.lineTo(x + half, top + h * 0.38f);
                t.lineTo(x - half, top + h * 0.38f);
                t.closePath();
            }
            t.append(new Rectangle2D.Float(x - w * 0.05f, ground - h * 0.12f, w * 0.1f, h * 0.14f), false);
            g.fill(t);
        }
    }
}
