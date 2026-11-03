import java.awt.*; import java.awt.geom.*; import java.awt.image.BufferedImage; import javax.imageio.ImageIO; import java.io.*; import java.nio.*; import java.util.*;

public class IconGen {
    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 256.0, size / 256.0);
        g.setColor(new Color(0xf2a33a));
        g.fill(new RoundRectangle2D.Double(0, 0, 256, 256, 112, 112));
        g.setColor(new Color(0x1a1206));
        g.setStroke(new BasicStroke(22, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D a = new Path2D.Double(); a.moveTo(70, 192); a.lineTo(128, 62); a.lineTo(186, 192); g.draw(a);
        g.fill(new RoundRectangle2D.Double(104, 152, 12, 24, 12, 12));
        g.fill(new RoundRectangle2D.Double(122, 141, 12, 46, 12, 12));
        g.fill(new RoundRectangle2D.Double(140, 152, 12, 24, 12, 12));
        g.dispose();
        return img;
    }
    static byte[] png(BufferedImage i) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(i, "png", o); return o.toByteArray(); }
    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]); File installer = new File(args[1]);
        for (int s : new int[]{32, 64, 256, 512}) ImageIO.write(draw(s), "png", new File(assets, "icon-" + s + ".png"));
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) pngs[i] = png(draw(sizes[i]));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            head.put((byte) (sizes[i] == 256 ? 0 : sizes[i])).put((byte) (sizes[i] == 256 ? 0 : sizes[i])).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        out.write(head.array()); for (byte[] p : pngs) out.write(p);
        try (FileOutputStream f = new FileOutputStream(new File(assets, "favicon.ico"))) { f.write(out.toByteArray()); }
        try (FileOutputStream f = new FileOutputStream(new File(installer, "app.ico"))) { f.write(out.toByteArray()); }
    }
}
