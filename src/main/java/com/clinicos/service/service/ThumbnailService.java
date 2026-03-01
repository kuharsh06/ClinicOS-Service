package com.clinicos.service.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Generates thumbnails for images and PDFs.
 * Images: resize to max 300px wide, maintain aspect ratio, output JPEG.
 * PDFs: render first page as 300px wide JPEG thumbnail.
 */
@Service
@Slf4j
public class ThumbnailService {

    private static final int THUMBNAIL_WIDTH = 300;
    private static final float JPEG_QUALITY = 0.8f;

    /**
     * Generate a thumbnail from the given file bytes.
     * @param fileBytes Original file bytes
     * @param mimeType MIME type of the original file
     * @return Thumbnail as JPEG bytes, or null if generation fails
     */
    public byte[] generateThumbnail(byte[] fileBytes, String mimeType) {
        try {
            if (mimeType != null && mimeType.startsWith("image/")) {
                return generateImageThumbnail(fileBytes);
            } else if ("application/pdf".equals(mimeType)) {
                return generatePdfThumbnail(fileBytes);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to generate thumbnail for type {}: {}", mimeType, e.getMessage());
            return null;
        }
    }

    private byte[] generateImageThumbnail(byte[] imageBytes) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (original == null) {
            log.warn("Could not read image for thumbnail generation");
            return null;
        }

        // Calculate dimensions maintaining aspect ratio
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        if (originalWidth <= THUMBNAIL_WIDTH) {
            // Already small enough — still convert to JPEG for consistency
            return toJpegBytes(original);
        }

        double ratio = (double) THUMBNAIL_WIDTH / originalWidth;
        int newHeight = (int) (originalHeight * ratio);

        // Resize
        BufferedImage thumbnail = new BufferedImage(THUMBNAIL_WIDTH, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumbnail.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, THUMBNAIL_WIDTH, newHeight, null);
        g.dispose();

        return toJpegBytes(thumbnail);
    }

    private byte[] generatePdfThumbnail(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            // Force 72 DPI (scale=1.0) regardless of PDF's internal resolution
            // Prevents 35MB+ bitmaps from high-res scanned documents
            BufferedImage pageImage = renderer.renderImage(0, 1.0f);

            // Resize to thumbnail width
            int originalWidth = pageImage.getWidth();
            int originalHeight = pageImage.getHeight();
            double ratio = (double) THUMBNAIL_WIDTH / originalWidth;
            int newHeight = (int) (originalHeight * ratio);

            BufferedImage thumbnail = new BufferedImage(THUMBNAIL_WIDTH, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumbnail.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(pageImage, 0, 0, THUMBNAIL_WIDTH, newHeight, null);
            g.dispose();

            return toJpegBytes(thumbnail);
        }
    }

    private byte[] toJpegBytes(BufferedImage image) throws IOException {
        // Ensure image is RGB (not ARGB) for JPEG
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            image = rgbImage;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * Get an InputStream from thumbnail bytes for storage.
     */
    public InputStream toInputStream(byte[] thumbnailBytes) {
        return new ByteArrayInputStream(thumbnailBytes);
    }
}
