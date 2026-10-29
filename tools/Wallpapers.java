import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;

/**
 * Generates Zen's built-in wallpapers (1920x1080). No dependencies:
 *   java tools/Wallpapers.java            (writes into the app; preview sheet → docs/images)
 * Every design keeps the left side calm (clock + app list) and puts its subject on the right.
 */
public class Wallpapers {
    static final int W = 1920, H = 1080;

    interface Shader { void px(int x, int y, float[] out); }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "app/src/main/assets/wallpapers");
        dir.mkdirs();
        String[] names = {"01-enso", "02-mist", "03-dunes", "04-ripples", "05-stone-garden", "06-still-sea"};
        BufferedImage[] imgs = {enso(), mist(), dunes(), ripples(), garden(), sea()};
        for (int i = 0; i < imgs.length; i++) {
            finish(imgs[i], 100 + i);
            jpeg(imgs[i], new File(dir, names[i] + ".jpg"));
            System.out.println("wrote " + names[i]);
        }
        File docs = new File("docs/images");
        docs.mkdirs();
        contactSheet(imgs, names, new File(docs, "wallpapers.jpg"));
    }

    // ---------- 1. Ensō: one dry-brush circle on warm ink-black ----------
    static BufferedImage enso() {
        BufferedImage im = render((x, y, o) -> {
            float d = dist(x, y, 1400, 540) / 1400f;
            set(o, mix(0x1b1815, 0x0a0908, (float) Math.pow(clamp(d), 0.8)));
            add(o, (fbm(x * 0.004f, y * 0.004f, 3, 5) - 0.5f) * 6f);
        });
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double cx = 1400, cy = 530, R = 250, th0 = Math.toRadians(205), sweep = Math.toRadians(322);
        int steps = 1800, bristles = 48;
        double[][] prev = new double[bristles][];
        for (int i = 0; i <= steps; i++) {
            double s = i / (double) steps, th = th0 + sweep * s;
            double rad = R + 7 * Math.sin(s * 9) + 14 * (fbm((float) s * 3, 0.5f, 9, 4) - 0.5);
            double w = 56 * (0.6 + 0.4 * Math.min(1, s / 0.05)) * (1 - 0.72 * Math.pow(s, 1.7));
            for (int b = 0; b < bristles; b++) {
                double side = b / (bristles - 1.0) - 0.5;
                double r = rad + side * w;
                double[] p = {cx + r * Math.cos(th), cy + r * Math.sin(th)};
                double dry = 0.12 + 0.78 * Math.pow(s, 2.2) + 0.3 * Math.abs(side) * 2 * s;
                boolean ink = noise((float) (s * 48 + b * 7.3), b * 1.7f, 99) > dry;
                if (ink && prev[b] != null) {
                    int a = (int) (150 + 90 * noise((float) s * 20, b, 5));
                    g.setColor(new Color(228, 220, 203, Math.min(255, a)));
                    g.setStroke(new BasicStroke((float) (w / bristles * 2.3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(prev[b][0], prev[b][1], p[0], p[1]));
                }
                prev[b] = ink ? p : null;
            }
        }
        // A small hanko seal, the only colour in the set.
        g.setColor(new Color(146, 50, 38, 210));
        g.fill(new RoundRectangle2D.Double(1690, 842, 30, 30, 5, 5));
        g.setColor(new Color(30, 14, 10, 140));
        g.setStroke(new BasicStroke(2.2f));
        g.draw(new Line2D.Double(1698, 850, 1712, 850));
        g.draw(new Line2D.Double(1705, 850, 1705, 865));
        g.draw(new Line2D.Double(1697, 865, 1713, 865));
        g.dispose();
        return im;
    }

    // ---------- 2. Mist: layered mountain ridges fading into fog, pale moon ----------
    static BufferedImage mist() {
        int layers = 5;
        float[][] ridge = new float[layers][W];
        for (int k = 0; k < layers; k++)
            for (int x = 0; x < W; x++) {
                float base = 560 + k * 92, amp = 190 - k * 24, f = 0.0011f * (1 + k * 0.45f);
                // Peaks rise toward the right so the clock side stays open sky.
                float lift = 70 * smooth(300, 1700, x) * (1 - k / (float) layers);
                ridge[k][x] = base - lift - amp * (fbm(x * f + k * 31.7f, k * 5.1f, 11 + k, 6) - 0.35f) * 1.5f;
            }
        int[] layerCol = {0x4a5360, 0x363e49, 0x252b33, 0x161a20, 0x090b0e};
        return render((x, y, o) -> {
            set(o, mix(0x0e1116, 0x303843, (float) Math.pow(smooth(0, 780, y), 1.4)));
            float d = dist(x, y, 1480, 250);
            add(o, 22 * (float) Math.exp(-Math.pow(d / 200, 2)));
            if (d < 44) blend(o, 0xd8dce1, clamp(44 - d));
            float[] c = layerAt(x, y, layers - 1, ridge, layerCol);
            if (c != null) { o[0] = c[0]; o[1] = c[1]; o[2] = c[2]; }
        });
    }

    static float[] layerAt(int x, float y, int k, float[][] ridge, int[] cols) {
        for (; k >= 0; k--) {
            float a = clamp(y + 0.5f - ridge[k][x]);
            if (a <= 0) continue;
            float fog = smooth(ridge[k][x], ridge[k][x] + 190, y) * (0.62f - k * 0.1f);
            fog += (fbm(x * 0.003f, y * 0.014f, 5, 5) - 0.5f) * 0.25f;
            float[] c = rgb(mix(cols[k], 0x303843, clamp(fog)));
            if (a < 1) {
                float[] behind = layerAt(x, y, k - 1, ridge, cols);
                if (behind == null) return null; // anti-aliased edge against sky is handled by grain
                for (int i = 0; i < 3; i++) c[i] = behind[i] + (c[i] - behind[i]) * a;
            }
            return c;
        }
        return null;
    }

    // ---------- 3. Dunes: warm dusk desert, soft crests ----------
    static BufferedImage dunes() {
        int n = 4;
        float[][] crest = new float[n][W + 1];
        float[] base = {560, 660, 780, 930}, amp = {38, 56, 74, 96}, f = {0.0021f, 0.0016f, 0.0012f, 0.0009f};
        for (int k = 0; k < n; k++)
            for (int x = 0; x <= W; x++)
                crest[k][x] = base[k] + amp[k] * (float) Math.sin(x * f[k] + k * 1.9)
                        + amp[k] * 0.45f * (float) Math.sin(x * f[k] * 2.3 + k * 0.7)
                        + 20 * (fbm(x * 0.002f, k * 3f, 21 + k, 4) - 0.5f);
        int[] cols = {0x55392b, 0x432c21, 0x2f1f17, 0x1b120e};
        return render((x, y, o) -> {
            set(o, mix(0x0c0908, 0x35241c, (float) Math.pow(smooth(0, 600, y), 1.5)));
            add(o, 26 * (float) Math.exp(-Math.pow((x - 1500) / 420.0, 2) - Math.pow((y - 560) / 130.0, 2)), 1f, 0.75f, 0.55f);
            for (int k = n - 1; k >= 0; k--) {
                float c = crest[k][x];
                if (y < c) continue;
                float slope = crest[k][Math.min(W, x + 1)] - c;
                float lit = clamp(0.5f - slope * 2.5f);
                float hl = (float) Math.exp(-(y - c) / 52.0) * (0.25f + 0.75f * lit);
                float rim = (float) Math.exp(-(y - c) / 3.5);
                set(o, cols[k]);
                add(o, hl * 40 + rim * 22 * (0.4f + 0.6f * lit), 1f, 0.78f, 0.62f);
                add(o, -(1 - lit) * 14);
                add(o, -12 * smooth(0, 160, y - c)); // valleys sink into shadow
                if (k == n - 1) add(o, (float) Math.sin((y - c) * 0.22 + x * 0.015 + fbm(x * 0.004f, y * 0.004f, 8, 3) * 8) * 2.5f);
                break;
            }
        });
    }

    // ---------- 4. Ripples: a single drop on still, dark water ----------
    static BufferedImage ripples() {
        return render((x, y, o) -> {
            set(o, mix(0x071214, 0x030709, smooth(0, H, y)));
            add(o, (fbm(x * 0.0025f, y * 0.0025f, 4, 5) - 0.5f) * 5);
            float v = ring(x, y, 1420, 540, 1f) + ring(x, y, 1700, 800, 0.45f);
            add(o, v * 15, 0.62f, 0.83f, 0.84f);
            add(o, (float) Math.pow(Math.max(0, v), 3) * 34, 0.75f, 0.9f, 0.9f);
            float d = dist(x, y, 1420, 540);
            if (d < 5) blend(o, 0xbfe3e4, clamp(5 - d) * 0.8f);
        });
    }

    static float ring(int x, int y, float cx, float cy, float strength) {
        float d = dist(x, y, cx, cy);
        float phase = d / (28 + d * 0.035f);
        return strength * (float) Math.sin(phase * Math.PI * 2) * (float) Math.exp(-d / 420) * smooth(0, 30, d);
    }

    // ---------- 5. Stone garden: raked sand rings around stones ----------
    static final float[][] STONES = {{1410, 560, 128, 84, -0.25f}, {1700, 850, 62, 44, 0.3f}, {1180, 890, 34, 24, 0.1f}};

    static BufferedImage garden() {
        float lambda = 22;
        return render((x, y, o) -> {
            float best = 1e9f; int bi = 0; float[] bu = null;
            for (int i = 0; i < STONES.length; i++) {
                float[] u = stoneFrame(x, y, STONES[i]);
                float d = stoneDist(u, STONES[i]);
                if (d < best) { best = d; bi = i; bu = u; }
            }
            int rings = bi == 0 ? 8 : bi == 1 ? 6 : 4;
            float phase = best < rings * lambda ? best / lambda : (y + 4 * (float) Math.sin(x * 0.002)) / lambda;
            set(o, 0x4a4843);
            add(o, (float) Math.sin(phase * Math.PI * 2) * 9);
            add(o, (rnd(x, y, 7) - 0.5f) * 12);
            // Contact shadow, offset down-right.
            float sd = 1e9f;
            for (float[] s : STONES) sd = Math.min(sd, stoneDist(stoneFrame(x - 10, y - 12, s), s));
            if (sd > 0) add(o, -34 * (float) Math.exp(-sd / 16));
            if (best < 0) {
                float light = clamp(0.55f - 0.45f * (bu[0] * 0.6f + bu[1] * 0.8f));
                set(o, mix(0x12110f, 0x4d4a43, light * 0.85f));
                add(o, (fbm(x * 0.02f, y * 0.02f, 31 + bi, 5) - 0.5f) * 26);
                add(o, -18 * smooth(-14, 0, best));
                float edge = clamp(-best);
                if (edge < 1) { float[] sand = rgb(0x3a3834); for (int i = 0; i < 3; i++) o[i] = sand[i] + (o[i] - sand[i]) * edge; }
            }
        });
    }

    static float[] stoneFrame(float x, float y, float[] s) {
        float dx = x - s[0], dy = y - s[1], c = (float) Math.cos(s[4]), sn = (float) Math.sin(s[4]);
        return new float[]{(dx * c + dy * sn) / s[2], (-dx * sn + dy * c) / s[3]};
    }

    static float stoneDist(float[] u, float[] s) {
        float en = (float) Math.sqrt(u[0] * u[0] + u[1] * u[1]);
        float ang = (float) Math.atan2(u[1], u[0]);
        en += (noise(ang * 1.6f + 10, s[0] * 0.01f, 3) - 0.5f) * 0.14f;
        return (en - 1) * (s[2] + s[3]) * 0.5f;
    }

    // ---------- 6. Still sea: dawn horizon, low sun, one reflection ----------
    static BufferedImage sea() {
        float hz = 660;
        return render((x, y, o) -> {
            if (y < hz) {
                set(o, mix(0x0b0e14, 0x3b3532, (float) Math.pow(smooth(0, hz, y), 2.2)));
                float band = smooth(360, 620, y) * (1 - smooth(620, hz, y));
                add(o, (fbm(x * 0.0016f, y * 0.035f, 41, 5) - 0.45f) * 14 * band);
                float d = dist(x, y, 1460, 612);
                add(o, 30 * (float) Math.exp(-Math.pow(d / 150, 2)), 1f, 0.86f, 0.72f);
                if (d < 17) blend(o, 0xeadfcd, clamp(17 - d));
            } else {
                float t = smooth(hz, H, y);
                set(o, mix(0x24242a, 0x06070a, (float) Math.pow(t, 0.6)));
                float streak = fbm(x * 0.0035f, y * 0.22f, 43, 4) - 0.5f;
                add(o, streak * 10 * (1 - t));
                float spread = 18 + (y - hz) * 0.22f;
                float col = (float) Math.exp(-Math.pow((x - 1460) / spread, 2));
                float glints = clamp((fbm(x * 0.02f, y * 0.35f, 47, 3) - 0.42f) * 3);
                add(o, col * glints * 70 * (1 - t * 0.85f), 1f, 0.88f, 0.74f);
                if (y < hz + 1) blend(o, 0x46403b, 0.6f);
            }
        });
    }

    // ---------- helpers ----------
    static BufferedImage render(Shader s) {
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[W * H];
        java.util.stream.IntStream.range(0, H).parallel().forEach(y -> {
            float[] o = new float[3];
            for (int x = 0; x < W; x++) { o[0] = o[1] = o[2] = 0; s.px(x, y, o); px[y * W + x] = pack(o); }
        });
        im.setRGB(0, 0, W, H, px, 0, W);
        return im;
    }

    /** Luminance film grain + gentle vignette; hides banding in dark gradients. */
    static void finish(BufferedImage im, long seed) {
        Random r = new Random(seed);
        int[] p = im.getRGB(0, 0, W, H, null, 0, W);
        for (int i = 0; i < p.length; i++) {
            float dx = (i % W - W / 2f) / (W / 2f), dy = (i / W - H / 2f) / (H / 2f);
            float m = 1 - 0.28f * smooth(0.45f, 1.45f, (float) Math.sqrt(dx * dx + dy * dy));
            float n = (float) r.nextGaussian() * 2.2f;
            int c = p[i];
            p[i] = pack(new float[]{((c >> 16) & 255) * m + n, ((c >> 8) & 255) * m + n, (c & 255) * m + n});
        }
        im.setRGB(0, 0, W, H, p, 0, W);
    }

    static void jpeg(BufferedImage im, File f) throws Exception {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.93f);
        f.delete();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            w.setOutput(out);
            w.write(null, new IIOImage(im, null, null), p);
        }
        w.dispose();
    }

    static void contactSheet(BufferedImage[] imgs, String[] names, File f) throws Exception {
        int tw = 640, th = 360, pad = 24, label = 34;
        BufferedImage sheet = new BufferedImage(3 * tw + 4 * pad, 2 * (th + label) + 3 * pad, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(11, 11, 16));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        for (int i = 0; i < imgs.length; i++) {
            int x = pad + (i % 3) * (tw + pad), y = pad + (i / 3) * (th + label + pad);
            g.drawImage(imgs[i], x, y, tw, th, null);
            g.setColor(new Color(156, 156, 168));
            g.drawString(names[i].substring(3).replace('-', ' '), x + 2, y + th + 24);
        }
        g.dispose();
        jpeg(sheet, f);
    }

    static int hash(int x, int y, int s) {
        int h = x * 374761393 + y * 668265263 + s * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }
    static float rnd(int x, int y, int s) { return (hash(x, y, s) & 0xffffff) / (float) 0xffffff; }
    static float noise(float x, float y, int s) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = x - xi, yf = y - yi, u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
        float a = rnd(xi, yi, s), b = rnd(xi + 1, yi, s), c = rnd(xi, yi + 1, s), d = rnd(xi + 1, yi + 1, s);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }
    static float fbm(float x, float y, int s, int oct) {
        float sum = 0, amp = 0.5f, f = 1, norm = 0;
        for (int i = 0; i < oct; i++) { sum += amp * noise(x * f, y * f, s + i * 17); norm += amp; amp *= 0.5f; f *= 2; }
        return sum / norm;
    }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    static float smooth(float e0, float e1, float x) { float t = clamp((x - e0) / (e1 - e0)); return t * t * (3 - 2 * t); }
    static float dist(float x, float y, float cx, float cy) { return (float) Math.hypot(x - cx, y - cy); }
    static float[] rgb(int c) { return new float[]{(c >> 16) & 255, (c >> 8) & 255, c & 255}; }
    static int mix(int a, int b, float t) {
        float[] x = rgb(a), y = rgb(b);
        return pack(new float[]{lerp(x[0], y[0], t), lerp(x[1], y[1], t), lerp(x[2], y[2], t)});
    }
    static void set(float[] o, int c) { float[] v = rgb(c); o[0] = v[0]; o[1] = v[1]; o[2] = v[2]; }
    static void add(float[] o, float v) { o[0] += v; o[1] += v; o[2] += v; }
    static void add(float[] o, float v, float r, float g, float b) { o[0] += v * r; o[1] += v * g; o[2] += v * b; }
    static void blend(float[] o, int c, float a) { float[] v = rgb(c); for (int i = 0; i < 3; i++) o[i] += (v[i] - o[i]) * a; }
    static int pack(float[] o) {
        int r = Math.round(Math.max(0, Math.min(255, o[0]))), g = Math.round(Math.max(0, Math.min(255, o[1]))), b = Math.round(Math.max(0, Math.min(255, o[2])));
        return (r << 16) | (g << 8) | b;
    }
}
