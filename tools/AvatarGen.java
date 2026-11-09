import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Draws the ten default avatars, ten cartoon monsters, once, from one description, into both formats the app needs: SVG for
 * the page and a 128 px PNG for the native windows (call popup and overlay), so the two never drift apart. Everything is
 * clipped to the round frame. Which monster a person gets is FNV-1a of their id, modulo ten, computed the same way in
 * ui.js and in AvatarService.
 * Run: java tools/AvatarGen.java src/main/resources/public/assets/avatars
 *
 * @author Angatu Sistemas
 */
public class AvatarGen {

    static final String INK = "#1a1206";
    static final String WHITE = "#fffaf2";

    record Part(String path, double cx, double cy, double r, String fill, String stroke, double width) {
    }

    record Avatar(String name, String background, List<Part> parts) {
    }

    /* A tiny builder so each monster reads as a list of shapes. */
    static final class Draw {
        final List<Part> parts = new ArrayList<>();

        Draw fill(String d, String color) {
            parts.add(new Part(d, 0, 0, 0, color, null, 0));
            return this;
        }

        Draw line(String d, String color, double width) {
            parts.add(new Part(d, 0, 0, 0, null, color, width));
            return this;
        }

        Draw dot(double cx, double cy, double r, String color) {
            parts.add(new Part(null, cx, cy, r, color, null, 0));
            return this;
        }

        /* A cartoon eye: white ball, pupil looking (dx, dy), and the shine that makes it alive. */
        Draw eye(double cx, double cy, double r, double dx, double dy) {
            dot(cx, cy, r, WHITE);
            double p = r * 0.5;
            dot(cx + dx, cy + dy, p, INK);
            return dot(cx + dx + p * 0.35, cy + dy - p * 0.4, Math.max(0.9, p * 0.32), WHITE);
        }
    }

    static List<Avatar> avatars() {
        List<Avatar> all = new ArrayList<>();
        // 0. Cyclops: one huge eye, two small horns, a toothy grin.
        all.add(new Avatar("ciclope", "#fde3bb", new Draw()
                .fill("M19 22 L15 8 L27 16 Z M45 22 L49 8 L37 16 Z", WHITE)
                .fill("M10 64 L10 38 C10 22 20 13 32 13 C44 13 54 22 54 38 L54 64 Z", "#f2a33a")
                .eye(32, 33, 10, 1.5, 1)
                .fill("M21 47 C26 55 38 55 43 47 Z", INK)
                .fill("M26 47.4 L28 51.5 L30.5 48.2 Z M33.5 48.2 L36 51.5 L38 47.4 Z", WHITE).parts));
        // 1. Drop: a teal droplet with wide eyes and a little "o" mouth.
        all.add(new Avatar("gota", "#cfeee9", new Draw()
                .fill("M32 7 C40 19 53 30 53 45 C53 59 43 66 32 66 C21 66 11 59 11 45 C11 30 24 19 32 7 Z", "#4fb3a5")
                .eye(24.5, 40, 6.5, 0.5, 1)
                .eye(39.5, 40, 6.5, -0.5, 1)
                .dot(32, 52, 2.8, INK)
                .dot(18, 49, 2.6, "#e5795a")
                .dot(46, 49, 2.6, "#e5795a").parts));
        // 2. Furball: spiky blue fur and a big happy grin.
        all.add(new Avatar("peludo", "#d6ecf8", new Draw()
                .fill("M7 64 L7 30 L12 24 L15 29 L19 19 L24 26 L28 16 L32 24 L36 16 L40 26 L45 19 L49 29 L52 24 L57 30 L57 64 Z", "#6fb3d9")
                .eye(24, 37, 7, 2, 0.5)
                .eye(40, 37, 7, 2, 0.5)
                .fill("M19 47 C25 57 39 57 45 47 Z", INK)
                .fill("M28 53.5 C30 50.5 34 50.5 36 53.5 C34 55 30 55 28 53.5 Z", "#e5795a").parts));
        // 3. Alien: antennae, three eyes, a wobbly mouth.
        all.add(new Avatar("alien", "#e0f0d2", new Draw()
                .line("M24 22 L17 9 M40 22 L47 9", INK, 2.6)
                .dot(17, 9, 3.6, "#f2a33a")
                .dot(47, 9, 3.6, "#f2a33a")
                .fill("M13 64 L13 40 C13 27 21 20 32 20 C43 20 51 27 51 40 L51 64 Z", "#8fbf6a")
                .eye(22, 37, 5, 0.5, 0.8)
                .eye(32, 31, 5.5, 0, 0.8)
                .eye(42, 37, 5, -0.5, 0.8)
                .line("M23 50 C27 53 29 47 32 50 C35 53 37 47 41 50", INK, 2.4).parts));
        // 4. Buck-toothed: sleepy lids, two big front teeth, freckles.
        all.add(new Avatar("dentuco", "#fbd9cd", new Draw()
                .dot(32, 41, 23, "#e5795a")
                .eye(24.5, 35, 6.5, 0, 1.5)
                .eye(39.5, 35, 6.5, 0, 1.5)
                .fill("M17.6 35 C17.6 26.6 31.4 26.6 31.4 35 Z M32.6 35 C32.6 26.6 46.4 26.6 46.4 35 Z", "#c95f43")
                .fill("M23 47 L41 47 C41 54 23 54 23 47 Z", INK)
                .fill("M27.6 47 L27.6 52.4 L31.4 52.4 L31.4 47 Z M32.6 47 L32.6 52.4 L36.4 52.4 L36.4 47 Z", WHITE)
                .dot(15.5, 43, 1.4, "#c95f43").dot(18.5, 46, 1.4, "#c95f43").dot(48.5, 43, 1.4, "#c95f43").dot(45.5, 46, 1.4, "#c95f43").parts));
        // 5. Ghost: a pale sheet with a wavy hem and an astonished face.
        all.add(new Avatar("fantasma", "#e3c77d", new Draw()
                .fill("M13 62 L13 32 C13 19 21 11 32 11 C43 11 51 19 51 32 L51 62 L45 56 L39 62 L32 56 L25 62 L19 56 Z", "#f6f1e8")
                .dot(25, 32, 4.6, INK).dot(26.4, 30.4, 1.4, WHITE)
                .dot(39, 32, 4.6, INK).dot(40.4, 30.4, 1.4, WHITE)
                .fill("M28.5 44 C28.5 38.5 35.5 38.5 35.5 44 C35.5 49.5 28.5 49.5 28.5 44 Z", INK)
                .dot(19, 40, 2.4, "#e5b9a8").dot(45, 40, 2.4, "#e5b9a8").parts));
        // 6. Robot monster: boxy head, mismatched eyes, grille mouth, antenna.
        all.add(new Avatar("robo", "#dfe4ec", new Draw()
                .line("M32 20 L32 10", INK, 2.6)
                .dot(32, 9, 3.6, "#e5795a")
                .fill("M13 64 L13 27 C13 22 16 19 21 19 L43 19 C48 19 51 22 51 27 L51 64 Z", "#9aa8bd")
                .fill("M9 33 L13 31 L13 41 L9 39 Z M55 33 L51 31 L51 41 L55 39 Z", "#6f7d93")
                .eye(24, 33, 7, 1, 0.5)
                .eye(41, 34, 4.6, 0.5, 0.5)
                .fill("M21 45 L43 45 L43 53 L21 53 Z", INK)
                .line("M26.5 45 L26.5 53 M32 45 L32 53 M37.5 45 L37.5 53", "#9aa8bd", 1.6).parts));
        // 7. Horned grump: curved horns, frowning brows, two fangs.
        all.add(new Avatar("chifrudo", "#eef0c8", new Draw()
                .fill("M17 27 C9 21 9 10 14 5 C16 14 21 18 26 20 Z M47 27 C55 21 55 10 50 5 C48 14 43 18 38 20 Z", WHITE)
                .fill("M10 64 L10 39 C10 24 20 16 32 16 C44 16 54 24 54 39 L54 64 Z", "#c2c25a")
                .eye(24, 35, 5.4, 1, 0.5)
                .eye(40, 35, 5.4, -1, 0.5)
                .line("M18 27 L28 31 M46 27 L36 31", INK, 2.8)
                .fill("M22 46 C28 51 36 51 42 46 Z", INK)
                .fill("M25 46.5 L27 51 L29 47.6 Z M35 47.6 L37 51 L39 46.5 Z", WHITE).parts));
        // 8. Octopus: round head, curling tentacles, a shy smile.
        all.add(new Avatar("polvo", "#ecd9c8", new Draw()
                .line("M20 40 C17 49 12 51 10 58 M26 43 C25 52 20 56 21 63 M32 44 L32 64 M38 43 C39 52 44 56 43 63 M44 40 C47 49 52 51 54 58", "#b8875f", 5.5)
                .dot(32, 29, 17, "#b8875f")
                .eye(25.5, 29, 5.6, 1, 0.6)
                .eye(38.5, 29, 5.6, -1, 0.6)
                .line("M27.5 37 C30 39.5 34 39.5 36.5 37", INK, 2.2)
                .dot(32, 17, 1.8, "#d7aa82").dot(26, 19, 1.4, "#d7aa82").dot(38, 19.5, 1.4, "#d7aa82").parts));
        // 9. Bat-eared: dark fur, pointy ears, big glowing eyes and tiny fangs.
        all.add(new Avatar("orelhudo", "#d7d0c4", new Draw()
                .fill("M17 32 L10 7 L29 22 Z M47 32 L54 7 L35 22 Z", "#5d554b")
                .fill("M17 26 L14 14 L24 22 Z M47 26 L50 14 L40 22 Z", "#e5795a")
                .dot(32, 39, 19, "#5d554b")
                .eye(25, 36, 6.4, 0.8, 0.8)
                .eye(39, 36, 6.4, -0.8, 0.8)
                .line("M27 47 C30 49.5 34 49.5 37 47", WHITE, 1.8)
                .fill("M28.6 47.8 L30 51.4 L31.4 48.6 Z M32.6 48.6 L34 51.4 L35.4 47.8 Z", WHITE).parts));
        return all;
    }

    static String svg(Avatar a) {
        StringBuilder out = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" width=\"64\" height=\"64\">");
        out.append("<defs><clipPath id=\"f\"><circle cx=\"32\" cy=\"32\" r=\"32\"/></clipPath></defs><g clip-path=\"url(#f)\">");
        out.append("<rect width=\"64\" height=\"64\" fill=\"").append(a.background()).append("\"/>");
        for (Part p : a.parts()) {
            String paint = " fill=\"" + (p.fill() == null ? "none" : p.fill()) + "\"" + (p.stroke() == null ? ""
                    : String.format(Locale.ROOT, " stroke=\"%s\" stroke-width=\"%s\" stroke-linecap=\"round\" stroke-linejoin=\"round\"", p.stroke(), num(p.width())));
            if (p.path() != null) {
                out.append("<path d=\"").append(p.path()).append('"').append(paint).append("/>");
            } else {
                out.append(String.format(Locale.ROOT, "<circle cx=\"%s\" cy=\"%s\" r=\"%s\"", num(p.cx()), num(p.cy()), num(p.r()))).append(paint).append("/>");
            }
        }
        return out.append("</g></svg>\n").toString();
    }

    static String num(double v) {
        if (v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    static BufferedImage png(Avatar a, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 64.0, size / 64.0);
        Shape frame = new Ellipse2D.Double(0, 0, 64, 64);
        g.setColor(Color.decode(a.background()));
        g.fill(frame);
        g.clip(frame);
        for (Part p : a.parts()) {
            Shape shape = p.path() != null ? path(p.path()) : new Ellipse2D.Double(p.cx() - p.r(), p.cy() - p.r(), p.r() * 2, p.r() * 2);
            if (p.fill() != null) {
                g.setColor(Color.decode(p.fill()));
                g.fill(shape);
            }
            if (p.stroke() != null) {
                g.setColor(Color.decode(p.stroke()));
                g.setStroke(new BasicStroke((float) p.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(shape);
            }
        }
        g.dispose();
        return img;
    }

    /* Only the commands the descriptions use: absolute M, L, C and Z. */
    static Path2D path(String d) {
        Path2D.Double path = new Path2D.Double();
        String[] t = d.replaceAll("([MLCZ])", " $1 ").trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            switch (t[i++]) {
                case "M" -> path.moveTo(Double.parseDouble(t[i++]), Double.parseDouble(t[i++]));
                case "L" -> path.lineTo(Double.parseDouble(t[i++]), Double.parseDouble(t[i++]));
                case "C" -> path.curveTo(Double.parseDouble(t[i++]), Double.parseDouble(t[i++]), Double.parseDouble(t[i++]),
                        Double.parseDouble(t[i++]), Double.parseDouble(t[i++]), Double.parseDouble(t[i++]));
                case "Z" -> path.closePath();
                default -> throw new IllegalArgumentException("unsupported path token " + t[i - 1]);
            }
        }
        return path;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        List<Avatar> all = avatars();
        for (int i = 0; i < all.size(); i++) {
            Files.writeString(dir.resolve("avatar-" + i + ".svg"), svg(all.get(i)), StandardCharsets.UTF_8);
            ImageIO.write(png(all.get(i), 128), "png", dir.resolve("avatar-" + i + ".png").toFile());
        }
    }
}
