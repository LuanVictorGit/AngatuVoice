package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.services.CallService;
import br.com.angatusistemas.voice.services.LiveHub;
import br.com.angatusistemas.voice.services.PeerNetwork;
import br.com.angatusistemas.voice.services.SettingsService;
import br.com.angatusistemas.voice.services.SoundboardService;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The sound wheel over a game: Right Shift held shows eight sounds around the screen's centre, the mouse direction or the
 * keys 1 to 8 pick one, and letting go plays it into the call. When the app itself is in front, the page draws its own wheel.
 *
 * @author Angatu Sistemas
 */
final class SoundWheel {

    private static final int SIZE = 400;
    private static final int OUTER = 180;
    private static final int INNER = 64;
    private static final int POLL_MS = 16;
    // Shift is also how capitals are typed: the wheel waits a moment of Shift alone before it shows.
    private static final int HOLD_MS = 180;
    private static final Color BACKDROP = new Color(15, 14, 13, 200);
    private static final Color SEGMENT = new Color(38, 34, 30, 230);
    private static final Color CHOSEN = new Color(0xf2a33a);
    private static final Color LINE = new Color(255, 255, 255, 40);
    private static final Color INK = new Color(0xece7e0);
    private static final Color INK_ON_CHOSEN = new Color(0x14100b);

    private static JWindow window;
    private static Timer poll;
    private static volatile boolean open;
    private static volatile boolean armed;
    private static volatile int chosen = -1;

    private SoundWheel() {
    }

    static boolean isOpen() {
        return open;
    }

    /* Called from the keyboard hook: only decides, the drawing happens on the Swing thread. */
    static void press() {
        if (open || armed || !SettingsService.flag("soundboard.enabled") || AppShell.focused() || !inCall()) {
            return;
        }
        armed = true;
        chosen = -1;
        SwingUtilities.invokeLater(() -> {
            Timer wait = new Timer(HOLD_MS, e -> {
                if (armed) {
                    open = true;
                    show();
                }
            });
            wait.setRepeats(false);
            wait.start();
        });
    }

    /* Any other key while Shift is down means typing, not the wheel. */
    static void otherKey() {
        if (armed && !open) {
            armed = false;
        }
    }

    static void pick(int slot) {
        if (open && SoundboardService.validSlot(slot)) {
            chosen = slot;
            SwingUtilities.invokeLater(() -> { if (window != null) window.repaint(); });
        }
    }

    static void cancel() {
        armed = false;
        if (open) {
            open = false;
            SwingUtilities.invokeLater(SoundWheel::hide);
        }
    }

    static void release() {
        armed = false;
        if (!open) {
            return;
        }
        open = false;
        int slot = chosen;
        SwingUtilities.invokeLater(SoundWheel::hide);
        if (slot >= 0) {
            Thread.startVirtualThread(() -> LiveHub.emit("soundboard.play", Map.of("slot", slot)));
        }
    }

    private static boolean inCall() {
        return !CallService.roomsOf(PeerNetwork.me().id()).isEmpty();
    }

    private static void show() {
        if (!open) {
            return;
        }
        if (window == null) {
            window = create();
        }
        Rectangle screen = screenAt(cursor());
        window.setBounds(screen.x + (screen.width - SIZE) / 2, screen.y + (screen.height - SIZE) / 2, SIZE, SIZE);
        window.repaint();
        window.setVisible(true);
        Overlay.passClicksThrough(window);
        if (poll == null) {
            poll = new Timer(POLL_MS, e -> follow());
        }
        poll.start();
    }

    private static void hide() {
        if (poll != null) {
            poll.stop();
        }
        if (window != null) {
            window.setVisible(false);
        }
    }

    /* Like Rust: the slice under the cursor, or the one it points at from the centre. A game that holds the cursor in
       the middle leaves the choice to the number keys. */
    private static void follow() {
        if (!open || window == null || !window.isShowing()) {
            return;
        }
        Point now = cursor();
        Point corner = window.getLocationOnScreen();
        int dx = now.x - (corner.x + SIZE / 2);
        int dy = now.y - (corner.y + SIZE / 2);
        if (dx * dx + dy * dy < INNER * INNER) {
            return;
        }
        double degrees = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        int slot = Math.floorMod((int) Math.round(degrees / (360.0 / SoundboardService.SLOTS)), SoundboardService.SLOTS);
        if (slot != chosen) {
            chosen = slot;
            window.repaint();
        }
    }

    /* In the same scaled coordinates as the window, whatever the display scaling. */
    private static Point cursor() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? new Point(-1, -1) : info.getLocation();
    }

    private static Rectangle screenAt(Point p) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration config = device.getDefaultConfiguration();
            if (config.getBounds().contains(p.x, p.y)) {
                return config.getBounds();
            }
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    private static JWindow create() {
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setFocusableWindowState(false);
        w.setFocusable(false);
        w.setBackground(new Color(0, 0, 0, 0));
        w.setType(java.awt.Window.Type.UTILITY);
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                paintWheel((Graphics2D) graphics.create());
            }
        };
        panel.setOpaque(false);
        w.setContentPane(panel);
        return w;
    }

    private static void paintWheel(Graphics2D g) {
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double c = SIZE / 2.0;
        g.setColor(BACKDROP);
        g.fill(new Ellipse2D.Double(c - OUTER - 10, c - OUTER - 10, (OUTER + 10) * 2, (OUTER + 10) * 2));
        double step = 360.0 / SoundboardService.SLOTS;
        Area hole = new Area(new Ellipse2D.Double(c - INNER, c - INNER, INNER * 2, INNER * 2));
        Font label = new Font("Segoe UI", Font.BOLD, 14);
        Font number = new Font("Segoe UI", Font.BOLD, 11);
        for (int i = 0; i < SoundboardService.SLOTS; i++) {
            // Slot 0 at the top, then clockwise; Java's arcs start at three o'clock and turn counter-clockwise.
            double start = 90 - i * step - step / 2;
            Area slice = new Area(new Arc2D.Double(c - OUTER, c - OUTER, OUTER * 2, OUTER * 2, start + 1, step - 2, Arc2D.PIE));
            slice.subtract(hole);
            boolean on = i == chosen;
            g.setColor(on ? CHOSEN : SEGMENT);
            g.fill(slice);
            g.setColor(LINE);
            g.setStroke(new BasicStroke(1f));
            g.draw(slice);
            double middle = Math.toRadians(i * step - 90);
            double r = (OUTER + INNER) / 2.0;
            int x = (int) Math.round(c + Math.cos(middle) * r);
            int y = (int) Math.round(c + Math.sin(middle) * r);
            g.setColor(on ? INK_ON_CHOSEN : INK);
            g.setFont(label);
            FontMetrics m = g.getFontMetrics();
            String name = SettingsService.get("soundboard." + i);
            g.drawString(name, x - m.stringWidth(name) / 2, y + 4);
            g.setFont(number);
            FontMetrics n = g.getFontMetrics();
            String key = Integer.toString(i + 1);
            g.drawString(key, x - n.stringWidth(key) / 2, y - 14);
        }
        g.setFont(new Font("Segoe UI", Font.BOLD, 12));
        FontMetrics m = g.getFontMetrics();
        String hint = chosen >= 0 ? "Solte para tocar" : "Escolha um som";
        g.setColor(INK);
        g.drawString(hint, (int) (c - m.stringWidth(hint) / 2.0), (int) c + 4);
        g.dispose();
    }
}
