package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.repository.DocumentRepository;
import com.chatbot.agent.repository.ChatbotRepository;
import org.apache.tika.Tika;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
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
    private final ChatbotRepository chatbotRepository;
    private final AzureSearchService azureSearchService;

    public DocumentService(VectorStoreService vectorStoreService,
                           DocumentRepository documentRepository,
                           Tika tika,
                           ChatbotRepository chatbotRepository,
                           AzureSearchService azureSearchService) {
        this.vectorStoreService = vectorStoreService;
        this.documentRepository = documentRepository;
        this.tika = tika;
        this.chatbotRepository = chatbotRepository;
        this.azureSearchService = azureSearchService;
    }

    public Model.Document uploadDocument(Long chatbotId, MultipartFile file) throws IOException {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] DocumentService.uploadDocument: chatbotId={}, fileName={}",
                requestId, chatbotId, file.getOriginalFilename());

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

        log.info("[requestId={}] Document uploaded: documentId={}", requestId, document.getId());
        processDocumentAsync(savedDocument);
        return document;
    }

    @Async
    public void processDocumentAsync(Model.Document document) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Processing document: documentId={}", requestId, document.getId());

        try {
            document.setStatus(Model.DocumentStatus.PROCESSING);
            documentRepository.updateStatus(document.getId(), document.getStatus());

            // Extract text with metadata
            DocumentWithMetadata docWithMetadata = extractTextWithMetadata(document.getFilePath());
            log.info("[requestId={}] Extracted {} pages", requestId, docWithMetadata.pages.size());

            // Chunk with metadata
            List<ChunkWithMetadata> chunksWithMetadata = chunkDocumentWithMetadata(
                    document.getFileName(),
                    docWithMetadata
            );

            log.info("[requestId={}] Created {} chunks with metadata",
                    requestId, chunksWithMetadata.size());

            // Index with metadata
            indexChunksWithMetadata(document, chunksWithMetadata);

            document.setStatus(Model.DocumentStatus.INDEXED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
            log.info("[requestId={}] Document indexed successfully", requestId);

        } catch (Exception e) {
            log.error("[requestId={}] Document processing failed", requestId, e);
            document.setStatus(Model.DocumentStatus.FAILED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
        }
    }

    /**
     * Extract text with page and section metadata
     */
    private DocumentWithMetadata extractTextWithMetadata(String filePath) throws IOException, TikaException {
        log.info("Extracting text with metadata from: {}", filePath);
        File file = new File(filePath);

        if (filePath.toLowerCase().endsWith(".pdf")) {
            return extractPdfWithMetadata(file);
        } else if (filePath.toLowerCase().endsWith(".docx")) {
            return extractDocxWithMetadata(file);
        } else {
            // Fallback: extract without detailed metadata
            String text = tika.parseToString(file);
            DocumentWithMetadata doc = new DocumentWithMetadata();
            PageContent page = new PageContent();
            page.pageNumber = 1;
            page.text = text;
            doc.pages.add(page);
            return doc;
        }
    }

    /**
     * Extract PDF with page-level metadata
     */
    private DocumentWithMetadata extractPdfWithMetadata(File file) throws IOException {
        DocumentWithMetadata docMetadata = new DocumentWithMetadata();

        try (PDDocument doc = Loader.loadPDF(file)) {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            int totalPages = doc.getNumberOfPages();

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(doc);

                PageContent page = new PageContent();
                page.pageNumber = pageNum;
                page.text = pageText;
                page.sectionTitle = detectSectionTitle(pageText);

                docMetadata.pages.add(page);
            }
        }

        return docMetadata;
    }

    /**
     * Extract DOCX with section metadata
     */
    private DocumentWithMetadata extractDocxWithMetadata(File file) throws IOException {
        DocumentWithMetadata docMetadata = new DocumentWithMetadata();

        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {

            String fullText = extractor.getText();

            // DOCX doesn't have explicit pages, treat as single page
            // or split by section breaks
            PageContent page = new PageContent();
            page.pageNumber = 1;
            page.text = fullText;
            page.sectionTitle = detectSectionTitle(fullText);

            docMetadata.pages.add(page);
        }

        return docMetadata;
    }

    /**
     * Detect section title from text (heuristic-based)
     */
    private String detectSectionTitle(String text) {
        // Look for common section patterns
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            // Common section headers: all caps, short, at start
            if (line.length() > 3 && line.length() < 50) {
                if (line.matches("^[A-Z][A-Z\\s]+$") || // ALL CAPS
                        line.matches("^\\d+\\.\\s+[A-Z].*") || // Numbered: "1. Experience"
                        line.matches("^[A-Z][a-z]+:.*")) { // Title: "Skills:"
                    return line;
                }
            }
        }
        return null;
    }

    /**
     * Chunk document with metadata preserved
     */
    private List<ChunkWithMetadata> chunkDocumentWithMetadata(String documentName,
                                                              DocumentWithMetadata docMetadata) {
        List<ChunkWithMetadata> chunks = new ArrayList<>();
        int globalChunkIndex = 0;
        int globalCharPosition = 0;

        for (PageContent page : docMetadata.pages) {
            String pageText = page.text;
            int pageStartPos = globalCharPosition;

            // Chunk this page
            List<String> pageChunks = chunkText(pageText);

            for (String chunkText : pageChunks) {
                ChunkWithMetadata chunk = new ChunkWithMetadata();
                chunk.chunkText = chunkText;
                chunk.documentName = documentName;
                chunk.pageNumber = page.pageNumber;
                chunk.sectionTitle = page.sectionTitle;
                chunk.chunkIndex = globalChunkIndex++;
                chunk.chunkStartPos = globalCharPosition;
                chunk.chunkEndPos = globalCharPosition + chunkText.length();

                chunks.add(chunk);
                globalCharPosition += chunkText.length();
            }

            globalCharPosition = pageStartPos + pageText.length();
        }

        return chunks;
    }

    /**
     * Chunk text into smaller pieces
     */
    private List<String> chunkText(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> chunks = new ArrayList<>();
        Pattern sentencePattern = Pattern.compile("(.{1," + chunkSize + "}(?<=\\s|\\n|\\r|(?<=[.!?])))");
        Matcher matcher = sentencePattern.matcher(text);

        while (matcher.find()) {
            String chunk = matcher.group().trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
        }

        return chunks;
    }

    /**
     * Index chunks with metadata
     */
    private void indexChunksWithMetadata(Model.Document document,
                                         List<ChunkWithMetadata> chunksWithMetadata) {
        Model.Chatbot chatbot = chatbotRepository.findById(document.getChatbotId())
                .orElseThrow(() -> new RuntimeException("Chatbot not found"));

        if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
            azureSearchService.uploadChunksWithMetadata(
                    document.getChatbotId(),
                    document.getId(),
                    chunksWithMetadata
            );
        } else {
            vectorStoreService.indexDocumentWithMetadata(
                    document.getChatbotId(),
                    document.getId(),
                    chunksWithMetadata
            );
        }
    }

    // Helper classes
    private static class DocumentWithMetadata {
        List<PageContent> pages = new ArrayList<>();
    }

    private static class PageContent {
        int pageNumber;
        String text;
        String sectionTitle;
    }

    public static class ChunkWithMetadata {
        public String chunkText;
        public String documentName;
        public Integer pageNumber;
        public String sectionTitle;
        public Integer chunkIndex;
        public Integer chunkStartPos;
        public Integer chunkEndPos;
    }
}
