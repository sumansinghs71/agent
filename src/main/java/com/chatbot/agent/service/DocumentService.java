package com.chatbot.agent.service;

import com.chatbot.agent.model.Model;
import com.chatbot.agent.repository.DocumentRepository;
import com.chatbot.agent.repository.ChatbotRepository;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

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
    private final ChatbotRepository chatbotRepository;
    private final AzureSearchService azureSearchService;

    @Autowired
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
        log.info("[requestId={}] DocumentService.uploadDocument request: chatbotId={}, fileName={}", requestId, chatbotId, file != null ? file.getOriginalFilename() : null);
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

        log.info("[requestId={}] DocumentService.uploadDocument success: chatbotId={}, documentId={}", requestId, chatbotId, document.getId());
        processDocumentAsync(savedDocument);
        return document;
    }

    @Async
    public void processDocumentAsync(Model.Document document) {
        String requestId = MDC.get("requestId");
        log.info("[requestId={}] DocumentService.processDocumentAsync request: documentId={}, fileName={}", requestId, document.getId(), document.getFileName());
        try {
            document.setStatus(Model.DocumentStatus.PROCESSING);
            documentRepository.updateStatus(document.getId(), document.getStatus());

            // Extract text
            String extractedText = extractText(document.getFilePath());
            log.info("[requestId={}] Extracted {} characters from {}", requestId, extractedText.length(), document.getFileName());

            // Chunk text and stream index
            streamChunksToVectorStore(extractedText, document);
            document.setStatus(Model.DocumentStatus.INDEXED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
            log.info("[requestId={}] Document {} successfully indexed", requestId, document.getFileName());
        } catch (Exception e) {
            log.error("[requestId={}] Document processing failed for {}", requestId, document.getFileName(), e);
            document.setStatus(Model.DocumentStatus.FAILED);
            documentRepository.updateStatus(document.getId(), document.getStatus());
        }
    }

    private String extractText(String filePath) throws IOException {
        log.info("Extracting text from: {}", filePath);
        File file = new File(filePath);
        if (!file.exists()) throw new IOException("File not found: " + filePath);

        boolean isDocx = filePath.toLowerCase().endsWith(".docx");
        try {
            if (!filePath.toLowerCase().endsWith(".pdf")) {
                String tikaText = extractTextWithTika(file);
                if (isDocx && (tikaText == null || tikaText.trim().isEmpty())) {
                    log.warn("Tika failed to extract text from .docx, trying POI fallback");
                    String poiText = extractTextWithPOIDocx(file);
                    if (poiText != null && !poiText.trim().isEmpty()) {
                        return poiText;
                    }
                }
                return tikaText;
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

    private String extractTextWithPOIDocx(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            log.error("POI failed to extract text from .docx: {}", file.getName(), e);
            return "";
        }
    }

    private void streamChunksToVectorStore(String text, Model.Document document) throws SQLException {
        String requestId = MDC.get("requestId");
        if (text == null || text.isEmpty()) {
            log.warn("[requestId={}] Text is empty for document {}", requestId, document.getId());
            return;
        }
        log.info("[requestId={}] DocumentService.streamChunksToVectorStore request: documentId={}, text.length={}", requestId, document.getId(), text != null ? text.length() : 0);
        //Pattern sentencePattern = Pattern.compile("(?s)(.{1," + chunkSize + "}(?<=\\.|\\n|\\r))");
        Pattern sentencePattern = Pattern.compile("(.{1," + chunkSize + "}(?<=\\s|\\n|\\r|(?<=[.!?])))");
        Matcher matcher = sentencePattern.matcher(text);

        List<String> chunks = new ArrayList<>();
        int lastEnd = 0;

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
                lastEnd = end;
            }
        }

        // Handle any remaining text
        if (lastEnd < text.length()) {
            String lastChunk = text.substring(lastEnd).trim();
            if (!lastChunk.isEmpty()) {
                chunks.add(lastChunk);
            }
        }

        // Determine where to upload based on chatbot type
        Model.Chatbot chatbot = chatbotRepository.findById(document.getChatbotId())
                .orElseThrow(() -> new RuntimeException("Chatbot not found with id: " + document.getChatbotId()));
        if (chatbot.getModelType() == Model.ModelType.AZURE_OPENAI) {
            log.info("[requestId={}] DocumentService.streamChunksToVectorStore uploading to AzureSearchService: chatbotId={}, documentId={}, chunks.size={}", requestId, document.getChatbotId(), document.getId(), chunks.size());
            azureSearchService.uploadChunksToAzureSearch(document.getChatbotId(), document.getId(), chunks);
        } else {
            log.info("[requestId={}] DocumentService.streamChunksToVectorStore uploading to VectorStoreService: chatbotId={}, documentId={}, chunks.size={}", requestId, document.getChatbotId(), document.getId(), chunks.size());
            vectorStoreService.indexDocument(document.getChatbotId(), document.getId(), chunks);
        }

        // Help GC
        text = null;
    }


}
