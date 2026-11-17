package com.rzx.jrec.desktop.dev;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the two binary assets the UI needs, so neither has to be hand-drawn
 * or checked in as an opaque blob with no provenance.
 * <p>
 * {@code grid.png} is a tiling dot field at ~6% white. It is the JavaFX equivalent
 * of the paper grain on the product page: without it the dark ground reads as flat
 * black, with it the window has a surface you can almost feel.
 * <p>
 * {@code app-icon.png} is the record tally — an orange ring around a solid dot on
 * a dark rounded square. Drawn with Java2D rather than exported from a design tool
 * so it can be regenerated at any size by rerunning this class.
 * <p>
 * Run: {@code java -cp jrec-desktop/target/classes com.rzx.jrec.desktop.dev.AssetGenerator <outDir>}
 */
public final class AssetGenerator {

    private static final int TILE = 24;
    private static final int ICON = 256;
    private static final Color SIGNAL = new Color(0xE2, 0x40, 0x0F);
    private static final Color GROUND = new Color(0x14, 0x12, 0x0C);

    private AssetGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0
                ? args[0]
                : "jrec-desktop/src/main/resources/com/rzx/jrec/desktop");
        Files.createDirectories(out);
        writeGrid(out.resolve("grid.png"));
        writeIcon(out.resolve("app-icon.png"));
        System.out.println("资源已写入 " + out.toAbsolutePath());
    }

    /** One dot on a 24px tile; tiled by JavaFX to cover the window. */
    private static void writeGrid(Path target) throws IOException {
        BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 15));
        g.fillRect(TILE / 2 - 1, TILE / 2 - 1, 2, 2);
        g.dispose();
        ImageIO.write(tile, "png", target.toFile());
    }

    private static void writeIcon(Path target) throws IOException {
        BufferedImage icon = new BufferedImage(ICON, ICON, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Rounded square ground, inset so the shape does not touch the bitmap edge.
        g.setColor(GROUND);
        g.fill(new RoundRectangle2D.Double(8, 8, ICON - 16, ICON - 16, 58, 58));

        g.setStroke(new BasicStroke(15f));
        g.setColor(SIGNAL);
        g.draw(new Ellipse2D.Double(74, 74, ICON - 148, ICON - 148));
        g.fill(new Ellipse2D.Double(ICON / 2.0 - 34, ICON / 2.0 - 34, 68, 68));

        g.dispose();
        ImageIO.write(icon, "png", target.toFile());
    }
}
