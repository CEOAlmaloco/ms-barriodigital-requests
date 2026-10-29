package cl.duoc.barriodigital.requests.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageAttachmentSanitizerTest {

    private final ImageAttachmentSanitizer sanitizer = new ImageAttachmentSanitizer();

    @Test
    void acceptsImageBytesAndUsesASafeGeneratedNameInsteadOfTheUploadName() throws Exception {
        byte[] png = imageBytes("png");
        MockMultipartFile file = new MockMultipartFile(
                "attachment",
                "..\\malicious.svg",
                "image/jpeg",
                png
        );

        ImageAttachmentSanitizer.SanitizedImage sanitized = sanitizer.sanitize(file);

        assertEquals("image/png", sanitized.contentType());
        assertEquals("evidencia.png", sanitized.fileName());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sanitized.content()));
        assertNotNull(decoded);
        assertEquals(2, decoded.getWidth());
    }

    @Test
    void rejectsSvgAndTextEvenWhenTheBrowserClaimsTheyAreImages() {
        MockMultipartFile file = new MockMultipartFile(
                "attachment",
                "foto.png",
                "image/png",
                "<svg onload=\"alert(1)\"></svg>".getBytes()
        );

        assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(file));
    }

    @Test
    void rejectsFilesAboveFiveMegabytesBeforeDecodingThem() {
        MockMultipartFile file = new MockMultipartFile(
                "attachment",
                "large.png",
                "image/png",
                new byte[(int) ImageAttachmentSanitizer.MAX_FILE_BYTES + 1]
        );

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> sanitizer.sanitize(file));

        assertEquals(413, exception.getStatusCode().value());
    }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
