package cl.duoc.barriodigital.requests.application.usecase;

public record RequestAttachmentContent(byte[] content, String contentType, String fileName) {
}
