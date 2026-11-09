package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.services.AvatarService;
import br.com.angatusistemas.voice.services.SettingsService;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * The corner popup that shows who is talking while the main window is in the background, like a game overlay.
 * It never takes focus and never receives clicks.
 *
 * @author Angatu Sistemas
 */
public final class Overlay {

    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int ROW = 40;
    private static final int WIDTH = 240;
    private static final int MARGIN = 24;
    private static final int MAX_ROWS = 8;
    private static final Color CARD = new Color(15, 14, 13, 225);
    private static final Color INK = new Color(0xece7e0);
    private static final Color RING = new Color(0x6cc08b);

    private static volatile List<Speaker> speakers = List.of();
    private static JWindow window;

    private Overlay() {
    }

    public static void update(List<Speaker> next) {
        speakers = next.size() > MAX_ROWS ? List.copyOf(next.subList(0, MAX_ROWS)) : List.copyOf(next);
        refresh();
    }

    public static void refresh() {
        SwingUtilities.invokeLater(Overlay::render);
    }

    private static void render() {
        boolean visible = !GraphicsEnvironment.isHeadless() && SettingsService.flag("overlay.enabled") && !AppShell.focused()
                && !speakers.isEmpty();
        if (!visible) {
            if (window != null) {
                window.setVisible(false);
            }
            return;
        }
        if (window == null) {
            window = create();
        }
        int height = speakers.size() * ROW + 16;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        String corner = SettingsService.get("overlay.corner");
        int x = corner.endsWith("l") ? screen.x + MARGIN : screen.x + screen.width - WIDTH - MARGIN;
        int y = corner.startsWith("t") ? screen.y + MARGIN : screen.y + screen.height - height - MARGIN;
        window.setBounds(x, y, WIDTH, height);
        window.repaint();
        window.setVisible(true);
        passClicksThrough(window);
    }

    /* AWT has no such option: without these styles Windows hands the card every click aimed at what is underneath it. */
    private static void passClicksThrough(JWindow w) {
        WinDef.HWND hwnd = new WinDef.HWND(Native.getWindowPointer(w));
        int style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE);
        int wanted = style | WinUser.WS_EX_LAYERED | WinUser.WS_EX_TRANSPARENT | WS_EX_NOACTIVATE;
        if (wanted != style) {
            User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, wanted);
        }
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
                paintRows((Graphics2D) graphics.create(), getWidth(), getHeight());
            }
        };
        panel.setOpaque(false);
        w.setContentPane(panel);
        return w;
    }

    private static void paintRows(Graphics2D g, int width, int height) {
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        boolean transparent = SettingsService.flag("overlay.transparent");
        if (!transparent) {
            g.setColor(CARD);
            g.fill(new RoundRectangle2D.Double(0, 0, width, height, 14, 14));
        }
        g.setFont(new Font("Segoe UI", Font.BOLD, 13));
        FontMetrics metrics = g.getFontMetrics();
        int y = 8;
        for (Speaker s : speakers) {
            Ellipse2D circle = new Ellipse2D.Double(12, y + 6, 28, 28);
            g.setColor(RING);
            g.fill(new Ellipse2D.Double(10, y + 4, 32, 32));
            Image photo = photo(s.id());
            if (photo != null) {
                Graphics2D clip = (Graphics2D) g.create();
                clip.clip(circle);
                clip.drawImage(photo, 12, y + 6, 28, 28, null);
                clip.dispose();
            } else {
                g.setColor(Color.getHSBColor((Math.abs(s.id().hashCode()) % 360) / 360f, 0.45f, 0.85f));
                g.fill(circle);
                g.setColor(new Color(0x14100b));
                String initial = s.name().isEmpty() ? "?" : s.name().substring(0, 1).toUpperCase();
                g.drawString(initial, 26 - metrics.stringWidth(initial) / 2, y + 25);
            }
            String label = ellipsize(s.name(), metrics, width - 64);
            if (transparent) {
                g.setColor(new Color(0, 0, 0, 200));
                g.drawString(label, 53, y + 26);
            }
            g.setColor(INK);
            g.drawString(label, 52, y + 25);
            y += ROW;
        }
        g.dispose();
    }

    private static Image photo(String id) {
        return AvatarService.picture(id).map(bytes -> {
            try {
                return (Image) ImageIO.read(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                return null;
            }
        }).orElse(null);
    }

    private static String ellipsize(String text, FontMetrics metrics, int max) {
        if (metrics.stringWidth(text) <= max) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && metrics.stringWidth(cut + "…") > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    public record Speaker(String id, String name) {
    }
}
