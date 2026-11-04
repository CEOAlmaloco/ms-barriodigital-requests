package cl.duoc.barriodigital.requests.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/** Valida el contenido real de la imagen, limita su tamaño y la vuelve a codificar sin metadatos. */
@Service
public class ImageAttachmentSanitizer {

    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 12_000_000;
    private static final int MAX_DIMENSION = 6000;

    public SanitizedImage sanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidImage();
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "La imagen no puede superar 5 MB");
        }

        try {
            byte[] original = file.getBytes();
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
                if (input == null) {
                    throw invalidImage();
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw invalidImage();
                }

                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    String normalizedFormat = switch (format) {
                        case "jpg", "jpeg" -> "jpeg";
                        case "png" -> "png";
                        default -> throw invalidImage();
                    };
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION
                            || (long) width * height > MAX_PIXELS) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "La imagen supera las dimensiones permitidas"
                        );
                    }

                    BufferedImage decoded = reader.read(0);
                    if (decoded == null) {
                        throw invalidImage();
                    }
                    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (!ImageIO.write(decoded, normalizedFormat, output)) {
                            throw invalidImage();
                        }
                        byte[] sanitized = output.toByteArray();
                        if (sanitized.length > MAX_FILE_BYTES) {
                            throw new ResponseStatusException(
                                    HttpStatus.PAYLOAD_TOO_LARGE,
                                    "La imagen procesada no puede superar 5 MB"
                            );
                        }
                        String contentType = normalizedFormat.equals("jpeg") ? "image/jpeg" : "image/png";
                        String fileName = normalizedFormat.equals("jpeg") ? "evidencia.jpg" : "evidencia.png";
                        return new SanitizedImage(sanitized, contentType, fileName);
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalidImage();
        }
    }

    private ResponseStatusException invalidImage() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Adjunta una imagen JPEG o PNG válida"
        );
    }

    public record SanitizedImage(byte[] content, String contentType, String fileName) {
    }
}
