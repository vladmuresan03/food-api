package com.foodfinder.storage;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Decodes uploaded images and produces thumbnails. Pure JDK ImageIO — no
 * extra dependencies. WebP is intentionally NOT supported here; the photo
 * validation rejects webp uploads to keep the storage contract simple.
 */
@Component
public class ImageProcessing {

    public static final int THUMBNAIL_MAX_WIDTH = 320;

    /** Result of decoding an image. */
    public record DecodedImage(int width, int height) {
    }

    public DecodedImage decode(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                throw new IOException("ImageIO could not decode file: " + path);
            }
            return new DecodedImage(img.getWidth(), img.getHeight());
        }
    }

    /**
     * Generates a thumbnail (max 320px width) and writes it to {@code targetPath}.
     * If the source is already smaller, the original is copied as-is (no upscale).
     */
    public void writeThumbnail(Path source, Path targetPath) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                throw new IOException("ImageIO could not decode file: " + source);
            }
            BufferedImage out;
            if (src.getWidth() <= THUMBNAIL_MAX_WIDTH) {
                out = src;
            } else {
                int targetH = (int) Math.round(src.getHeight() * ((double) THUMBNAIL_MAX_WIDTH / src.getWidth()));
                BufferedImage scaled = new BufferedImage(THUMBNAIL_MAX_WIDTH, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, THUMBNAIL_MAX_WIDTH, targetH, null);
                g.dispose();
                out = scaled;
            }
            Files.createDirectories(targetPath.getParent());
            // write as JPEG; transparency is flattened over white for visual consistency
            BufferedImage rgb = out;
            if (out.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage conv = new BufferedImage(out.getWidth(), out.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = conv.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, out.getWidth(), out.getHeight());
                g.drawImage(out, 0, 0, null);
                g.dispose();
                rgb = conv;
            }
            try (var outStream = Files.newOutputStream(targetPath,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ImageIO.write(rgb, "jpg", outStream);
            }
        }
    }
}
