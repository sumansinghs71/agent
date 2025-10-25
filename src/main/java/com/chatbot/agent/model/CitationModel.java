package com.chatbot.agent.model;

import lombok.Data;
import java.util.List;

public class CitationModel {

    @Data
    public static class ChunkWithMetadata {
        private String chunkText;
        private String documentName;
        private Integer pageNumber;
        private String sectionTitle;
        private Long documentId;
        private Integer chunkIndex;
        private Float similarityScore;
        private Integer chunkStartPos;
        private Integer chunkEndPos;
    }

    @Data
    public static class Citation {
        private Integer citationId;      // [1], [2], [3]
        private String documentName;
        private Integer pageNumber;
        private String sectionTitle;
        private String excerpt;          // snippet from chunk (max 150 chars)
        private Float confidence;        // 0.0 to 1.0
        private String chunkId;          // for exact reference: "doc_5_chunk_12"
        private Integer startPosition;   // character position in document
        private Integer endPosition;
    }

    @Data
    public static class ResponseWithCitations {
        private String answer;
        private List<Citation> citations;
        private CitationMetadata metadata;
    }

    @Data
    public static class CitationMetadata {
        private Integer totalSources;
        private Float avgConfidence;
        private List<String> documentsUsed;
        private Integer totalChunksRetrieved;
        private Integer citationsAdded;
    }

    @Data
    public static class Sentence {
        private String text;
        private Integer startIndex;
        private Integer endIndex;
    }
}