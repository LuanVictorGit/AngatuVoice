package br.com.angatusistemas.voice.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The native loading surface shown the instant the window opens, while Chromium starts behind it.
 *
 * @author Angatu Sistemas
 */
final class SplashPanel extends JPanel {

    static final Color BACKGROUND = new Color(0x0f0e0d);
    private static final Color INK = new Color(0xece7e0);
    private static final Color MUTED = new Color(0xa29a90);
    private static final Color TRACK = new Color(0x27231f);
    private static final Color ACCENT = new Color(0xf2a33a);
    private static final int LOGO = 88;
    private static final int BAR_WIDTH = 180;

    private final Image logo = load();
    private final Timer timer = new Timer(16, e -> repaint());
    private final long started = System.currentTimeMillis();
    private volatile String status = "Iniciando…";
    private volatile boolean failed;

    SplashPanel() {
        setBackground(BACKGROUND);
        setOpaque(true);
        timer.start();
    }

    void status(String text) {
        status = text;
    }

    void failed(String text) {
        failed = true;
        status = text;
        SwingUtilities.invokeLater(timer::stop);
        repaint();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int cx = getWidth() / 2;
        int top = getHeight() / 2 - 90;
        if (logo != null) {
            g.drawImage(logo, cx - LOGO / 2, top, LOGO, LOGO, null);
        }
        g.setFont(new Font("Segoe UI", Font.BOLD, 22));
        center(g, "Angatu Voice", cx, top + LOGO + 40, INK);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        center(g, status, cx, top + LOGO + 68, failed ? new Color(0xe5645a) : MUTED);
        if (!failed) {
            int y = top + LOGO + 90;
            g.setColor(TRACK);
            g.fill(new RoundRectangle2D.Double(cx - BAR_WIDTH / 2.0, y, BAR_WIDTH, 4, 4, 4));
            double phase = ((System.currentTimeMillis() - started) % 1400) / 1400.0;
            double eased = 0.5 - Math.cos(phase * Math.PI) / 2;
            int segment = 60;
            double x = cx - BAR_WIDTH / 2.0 + eased * (BAR_WIDTH - segment);
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(1));
            g.fill(new RoundRectangle2D.Double(x, y, segment, 4, 4, 4));
        }
        g.dispose();
    }

    private static void center(Graphics2D g, String text, int cx, int baseline, Color color) {
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(color);
        g.drawString(text, cx - metrics.stringWidth(text) / 2, baseline);
    }

    private static Image load() {
        try (InputStream in = SplashPanel.class.getResourceAsStream("/public/assets/icon-256.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }
}
