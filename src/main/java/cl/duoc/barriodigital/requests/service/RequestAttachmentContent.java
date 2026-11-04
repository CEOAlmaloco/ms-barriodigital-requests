package cl.duoc.barriodigital.requests.service;

public record RequestAttachmentContent(byte[] content, String contentType, String fileName) {
}
