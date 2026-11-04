package br.com.angatusistemas.voice.desktop;

import br.com.angatusistemas.voice.services.AvatarService;
import br.com.angatusistemas.voice.services.CallService;
import br.com.angatusistemas.voice.services.LiveHub;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The incoming-call card shown over other applications while the main window is hidden or in the background, so a call
 * can be answered or declined without looking for the app. It never takes the keyboard focus away from a game.
 *
 * @author Angatu Sistemas
 */
final class CallPopup {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 148;
    private static final int MARGIN = 24;
    private static final int PULSE_MS = 33;
    private static final double PULSE_PERIOD_MS = 1_400;
    private static final Color CARD = new Color(0x1d1a17);
    private static final Color LINE = new Color(0x332d27);
    private static final Color INK = new Color(0xece7e0);
    private static final Color MUTED = new Color(0xa29a90);
    private static final Color ANSWER = new Color(0x6cc08b);
    private static final Color ANSWER_INK = new Color(0x0c1a11);
    private static final Color DECLINE = new Color(0xe5675f);
    private static final Rectangle ANSWER_BUTTON = new Rectangle(16, 98, 138, 36);
    private static final Rectangle DECLINE_BUTTON = new Rectangle(166, 98, 138, 36);

    private static volatile List<CallService.Incoming> calls = List.of();
    private static JWindow window;
    private static Timer pulse;
    private static Image photo;
    private static String photoOwner = "";
    private static Point hover;

    private CallPopup() {
    }

    static void update(List<CallService.Incoming> next) {
        calls = next;
        refresh();
    }

    static void refresh() {
        SwingUtilities.invokeLater(CallPopup::render);
    }

    private static void render() {
        boolean visible = !GraphicsEnvironment.isHeadless() && !calls.isEmpty() && !AppShell.focused();
        if (!visible) {
            if (window != null) {
                window.setVisible(false);
                pulse.stop();
            }
            return;
        }
        if (window == null) {
            create();
        }
        loadPhoto(calls.getFirst().callerId());
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setBounds(screen.x + screen.width - WIDTH - MARGIN, screen.y + screen.height - HEIGHT - MARGIN, WIDTH, HEIGHT);
        window.setVisible(true);
        pulse.start();
    }

    private static void create() {
        window = new JWindow();
        window.setAlwaysOnTop(true);
        window.setFocusableWindowState(false);
        window.setBackground(new Color(0, 0, 0, 0));
        window.setType(java.awt.Window.Type.UTILITY);
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                paintCard((Graphics2D) graphics.create(), getWidth(), getHeight());
            }
        };
        panel.setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                hover = e.getPoint();
                boolean overButton = ANSWER_BUTTON.contains(hover) || DECLINE_BUTTON.contains(hover);
                panel.setCursor(Cursor.getPredefinedCursor(overButton ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                click(e.getPoint());
            }
        };
        panel.addMouseListener(mouse);
        panel.addMouseMotionListener(mouse);
        window.setContentPane(panel);
        pulse = new Timer(PULSE_MS, e -> window.repaint());
    }

    private static void click(Point point) {
        if (calls.isEmpty()) {
            return;
        }
        String room = calls.getFirst().room();
        if (ANSWER_BUTTON.contains(point)) {
            AppShell.show();
            Thread.startVirtualThread(() -> LiveHub.emit("call.action", Map.of("action", "answer", "room", room)));
        } else if (DECLINE_BUTTON.contains(point)) {
            Thread.startVirtualThread(() -> LiveHub.emit("call.action", Map.of("action", "decline", "room", room)));
        }
    }

    private static void paintCard(Graphics2D g, int width, int height) {
        List<CallService.Incoming> current = calls;
        if (current.isEmpty()) {
            g.dispose();
            return;
        }
        CallService.Incoming call = current.getFirst();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(CARD);
        g.fill(new RoundRectangle2D.Double(0, 0, width - 1, height - 1, 16, 16));
        g.setColor(LINE);
        g.draw(new RoundRectangle2D.Double(0, 0, width - 1, height - 1, 16, 16));
        paintAvatar(g, call);
        g.setColor(INK);
        g.setFont(new Font("Segoe UI", Font.BOLD, 15));
        g.drawString(ellipsize(call.callerName(), g.getFontMetrics(), width - 104), 88, 42);
        g.setColor(MUTED);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.drawString(subtitle(current.size() - 1), 88, 62);
        paintButton(g, ANSWER_BUTTON, "Atender", ANSWER, ANSWER_INK);
        paintButton(g, DECLINE_BUTTON, "Recusar", CARD, DECLINE);
        g.dispose();
    }

    private static String subtitle(int waiting) {
        return switch (waiting) {
            case 0 -> "Está chamando você";
            case 1 -> "Mais 1 chamada esperando";
            default -> "Mais " + waiting + " chamadas esperando";
        };
    }

    private static void paintAvatar(Graphics2D g, CallService.Incoming call) {
        double phase = (System.currentTimeMillis() % (long) PULSE_PERIOD_MS) / PULSE_PERIOD_MS;
        double radius = 26 + phase * 10;
        int alpha = (int) ((1 - phase) * 170);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(ANSWER.getRed(), ANSWER.getGreen(), ANSWER.getBlue(), alpha));
        g.draw(new Ellipse2D.Double(46 - radius, 46 - radius, radius * 2, radius * 2));
        Ellipse2D circle = new Ellipse2D.Double(22, 22, 48, 48);
        if (photo != null) {
            Graphics2D clip = (Graphics2D) g.create();
            clip.clip(circle);
            clip.drawImage(photo, 22, 22, 48, 48, null);
            clip.dispose();
            return;
        }
        g.setColor(Color.getHSBColor((Math.abs(call.callerId().hashCode()) % 360) / 360f, 0.45f, 0.85f));
        g.fill(circle);
        g.setColor(new Color(0x14100b));
        g.setFont(new Font("Segoe UI", Font.BOLD, 20));
        String initial = call.callerName().isEmpty() ? "?" : call.callerName().substring(0, 1).toUpperCase();
        g.drawString(initial, 46 - g.getFontMetrics().stringWidth(initial) / 2, 53);
    }

    private static void paintButton(Graphics2D g, Rectangle box, String label, Color fill, Color ink) {
        boolean hovered = hover != null && box.contains(hover);
        g.setColor(hovered ? fill.brighter() : fill);
        g.fill(new RoundRectangle2D.Double(box.x, box.y, box.width, box.height, 10, 10));
        g.setStroke(new BasicStroke(1f));
        g.setColor(ink);
        g.draw(new RoundRectangle2D.Double(box.x, box.y, box.width, box.height, 10, 10));
        g.setFont(new Font("Segoe UI", Font.BOLD, 13));
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(label, box.x + (box.width - metrics.stringWidth(label)) / 2,
                box.y + (box.height + metrics.getAscent() - metrics.getDescent()) / 2);
    }

    private static void loadPhoto(String id) {
        if (id.equals(photoOwner)) {
            return;
        }
        photoOwner = id;
        photo = AvatarService.bytes(id).map(bytes -> {
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
}
