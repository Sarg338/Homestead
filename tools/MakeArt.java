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
import javax.imageio.ImageIO;

/**
 * Generates the launcher icon and the Leanback banner, so the art lives in source rather
 * than as binaries and matches the gradient HomeGrid draws (BL #6991C7 -> TR #A3BDED).
 *
 * Run:  java tools/MakeArt.java <outDir>
 */
public final class MakeArt {

    private static final Color C1 = new Color(0x6991C7);
    private static final Color C2 = new Color(0xA3BDED);
    private static final Color INK = new Color(0x0B1F38);

    public static void main(String[] a) throws Exception {
        File out = new File(a.length > 0 ? a[0] : ".");
        out.mkdirs();
        ImageIO.write(icon(192), "png", new File(out, "ic_launcher.png"));
        ImageIO.write(banner(320, 180), "png", new File(out, "banner.png"));
        System.out.println("wrote ic_launcher.png (192x192) and banner.png (320x180) to " + out);
    }

    private static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Bottom-left to top-right, the same axis HomeGrid uses for the wallpaper.
        g.setPaint(new GradientPaint(0, img.getHeight(), C1, img.getWidth(), 0, C2));
        return g;
    }

    private static BufferedImage icon(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.fill(new RoundRectangle2D.Float(0, 0, s, s, s * 0.22f, s * 0.22f));
        house(g, s * 0.5f, s * 0.52f, s * 0.30f);
        g.dispose();
        return img;
    }

    private static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, 10, 10));

        house(g, w * 0.22f, h * 0.46f, h * 0.26f);

        g.setColor(Color.WHITE);
        g.setFont(pick(Font.BOLD, h * 0.17f));
        g.drawString("Homestead", w * 0.40f, h * 0.55f);

        g.setColor(new Color(255, 255, 255, 170));
        g.setFont(pick(Font.PLAIN, h * 0.082f));
        g.drawString("your Fire TV, reclaimed", w * 0.40f, h * 0.77f);
        g.dispose();
        return img;
    }

    /** Prefer a real UI face, but never fail the build over a missing font. */
    private static Font pick(int style, float size) {
        String[] want = {"Segoe UI", "Roboto", "Helvetica Neue", "DejaVu Sans", "SansSerif"};
        for (String name : want) {
            Font f = new Font(name, style, 12);
            if (f.getFamily().equalsIgnoreCase(name) || name.equals("SansSerif")) {
                return f.deriveFont(style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, (int) size);
    }

    /** A simple house: gabled roof over a body, with the doorway knocked back out. */
    private static void house(Graphics2D g, float cx, float cy, float r) {
        Path2D.Float roof = new Path2D.Float();
        roof.moveTo(cx, cy - r);
        roof.lineTo(cx + r, cy);
        roof.lineTo(cx - r, cy);
        roof.closePath();

        Area house = new Area(roof);
        house.add(new Area(new Rectangle2D.Float(
                cx - r * 0.62f, cy - r * 0.04f, r * 1.24f, r * 0.92f)));
        // Door is cut out, not painted, so the gradient shows through at small sizes.
        house.subtract(new Area(new RoundRectangle2D.Float(
                cx - r * 0.20f, cy + r * 0.30f, r * 0.40f, r * 0.58f, r * 0.16f, r * 0.16f)));

        g.setColor(new Color(INK.getRed(), INK.getGreen(), INK.getBlue(), 60));
        g.translate(0, r * 0.045f);
        g.fill(house);
        g.translate(0, -r * 0.045f);

        g.setColor(Color.WHITE);
        g.fill(house);
    }
}
