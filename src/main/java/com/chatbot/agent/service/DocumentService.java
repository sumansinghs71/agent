package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.repository.DocumentRepository;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.*;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    @Value("${document.upload-dir}")
    private String uploadDir;

    @Value("${document.chunk-size}")
    private int chunkSize;

    @Value("${document.chunk-overlap}")
    private int chunkOverlap;

    private final VectorStoreService vectorStoreService;
    private final DocumentRepository documentRepository;
    private final Tika tika;

    @Autowired
    public DocumentService(VectorStoreService vectorStoreService,
                           DocumentRepository documentRepository,
                           Tika tika) {
        this.vectorStoreService = vectorStoreService;
        this.documentRepository = documentRepository;
        this.tika = tika;
    }

    public Model.Document uploadDocument(Long chatbotId, MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = uploadPath.resolve(fileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        Model.Document document = new Model.Document();
        document.setChatbotId(chatbotId);
        document.setFileName(file.getOriginalFilename());
        document.setFilePath(filePath.toString());
        document.setStatus(Model.DocumentStatus.UPLOADED);
        Model.Document savedDocument = documentRepository.save(document);

        processDocumentAsync(savedDocument);
        return document;
    }

    @Async
    public void processDocumentAsync(Model.Document document) {
        try {
            document.setStatus(Model.DocumentStatus.PROCESSING);
            documentRepository.updateStatus(document.getId(), document.getStatus());

            // Extract text
            String extractedText = extractText(document.getFilePath());
            log.info("Extracted {} characters from {}", extractedText.length(), document.getFileName());

            // Chunk text and stream index
            streamChunksToVectorStore(extractedText, document);

            document.setStatus(Model.DocumentStatus.INDEXED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
            log.info("Document {} successfully indexed", document.getFileName());
        } catch (Exception e) {
            log.error("Document processing failed for {}", document.getFileName(), e);
            document.setStatus(Model.DocumentStatus.FAILED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
        }
    }

    private String extractText(String filePath) throws IOException {
        log.info("Extracting text from: {}", filePath);
        File file = new File(filePath);
        if (!file.exists()) throw new IOException("File not found: " + filePath);

        try {
            if (!filePath.toLowerCase().endsWith(".pdf")) {
                return extractTextWithTika(file);
            }
            return extractTextWithPDFBoxPaged(file);
        } catch (Exception e1) {
            log.error("Primary extraction failed, trying fallback", e1);
            try {
                return filePath.toLowerCase().endsWith(".pdf")
                        ? extractTextWithTika(file)
                        : extractTextWithPDFBoxPaged(file);
            } catch (Exception e2) {
                throw new IOException("All extraction methods failed: " + e1.getMessage() + " | " + e2.getMessage());
            }
        }
    }

    private String extractTextWithTika(File file) throws IOException, TikaException {
        try (InputStream stream = new FileInputStream(file)) {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());
            return tika.parseToString(stream, metadata);
        }
    }

    private String extractTextWithPDFBoxPaged(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(file)) {
            if (doc.isEncrypted()) doc.setAllSecurityToBeRemoved(true);
            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = doc.getNumberOfPages();
            for (int i = 1; i <= totalPages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                sb.append(stripper.getText(doc)).append("\n");
            }
        }
        return sb.toString();
    }

    private void streamChunksToVectorStore(String text, Model.Document document) throws SQLException {
        Pattern sentencePattern = Pattern.compile("(?s)(.{1," + chunkSize + "}(?<=\\.|\\n|\\r))");
        Matcher matcher = sentencePattern.matcher(text);

        int chunkIndex = 0;
        int lastEnd = 0;
        List<String> chunks = new ArrayList<>();

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String chunk = text.substring(start, end).trim();

            if (!chunk.isEmpty()) {
                vectorStoreService.indexSingleChunk(document.getChatbotId(), document.getId(), chunkIndex++, chunk);
                lastEnd = end;
            }
        }

        // Handle any remaining text
        if (lastEnd < text.length()) {
            String lastChunk = text.substring(lastEnd).trim();
            if (!lastChunk.isEmpty()) {
                vectorStoreService.indexSingleChunk(document.getChatbotId(), document.getId(), chunkIndex, lastChunk);
            }
        }

        // Help GC
        text = null;
    }
}
