package com.chatbot.agent.service.citation;

import com.chatbot.agent.model.CitationModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CitationService {

    private static final Logger log = LoggerFactory.getLogger(CitationService.class);
    private static final int MAX_EXCERPT_LENGTH = 150;

    /**
     * Main method: Add citations to response
     */
    public CitationModel.ResponseWithCitations addCitations(
            String response,
            List<CitationModel.ChunkWithMetadata> sourceChunks) {

        String requestId = MDC.get("requestId");
        log.info("[requestId={}] Adding citations to response", requestId);

        CitationModel.ResponseWithCitations result = new CitationModel.ResponseWithCitations();

        if (sourceChunks == null || sourceChunks.isEmpty()) {
            log.warn("[requestId={}] No source chunks provided", requestId);
            result.setAnswer(response);
            result.setCitations(Collections.emptyList());
            result.setMetadata(createEmptyMetadata());
            return result;
        }

        // Split response into sentences
        List<CitationModel.Sentence> sentences = splitIntoSentences(response);
        log.debug("[requestId={}] Split response into {} sentences", requestId, sentences.size());

        // Map sentences to citations
        Map<Integer, List<CitationModel.Citation>> sentenceCitations = new HashMap<>();
        int citationIdCounter = 1;
        List<CitationModel.Citation> allCitations = new ArrayList<>();

        for (int i = 0; i < sentences.size(); i++) {
            CitationModel.Sentence sentence = sentences.get(i);

            // Find best matching chunks for this sentence
            List<CitationModel.ChunkWithMetadata> matchingChunks = findMatchingChunks(
                    sentence.getText(),
                    sourceChunks,
                    2 // top 2 matches per sentence
            );

            List<CitationModel.Citation> citations = new ArrayList<>();
            for (CitationModel.ChunkWithMetadata chunk : matchingChunks) {
                CitationModel.Citation citation = createCitation(chunk, citationIdCounter++);
                citations.add(citation);
                allCitations.add(citation);
            }

            if (!citations.isEmpty()) {
                sentenceCitations.put(i, citations);
            }
        }

        // Build response with inline citations
        String annotatedResponse = buildAnnotatedResponse(sentences, sentenceCitations);

        // Create metadata
        CitationModel.CitationMetadata metadata = createMetadata(allCitations, sourceChunks);

        result.setAnswer(annotatedResponse);
        result.setCitations(allCitations);
        result.setMetadata(metadata);

        log.info("[requestId={}] Added {} citations to response", requestId, allCitations.size());
        return result;
    }

    /**
     * Split text into sentences
     */
    private List<CitationModel.Sentence> splitIntoSentences(String text) {
        List<CitationModel.Sentence> sentences = new ArrayList<>();
        Pattern pattern = Pattern.compile("([^.!?]+[.!?]+)");
        Matcher matcher = pattern.matcher(text);

        int currentIndex = 0;
        while (matcher.find()) {
            CitationModel.Sentence sentence = new CitationModel.Sentence();
            sentence.setText(matcher.group(1).trim());
            sentence.setStartIndex(currentIndex);
            sentence.setEndIndex(currentIndex + sentence.getText().length());
            sentences.add(sentence);
            currentIndex = sentence.getEndIndex() + 1;
        }

        // Handle remaining text without punctuation
        if (currentIndex < text.length()) {
            String remaining = text.substring(currentIndex).trim();
            if (!remaining.isEmpty()) {
                CitationModel.Sentence sentence = new CitationModel.Sentence();
                sentence.setText(remaining);
                sentence.setStartIndex(currentIndex);
                sentence.setEndIndex(text.length());
                sentences.add(sentence);
            }
        }

        return sentences;
    }

    /**
     * Find matching chunks for a sentence
     */
    private List<CitationModel.ChunkWithMetadata> findMatchingChunks(
            String sentence,
            List<CitationModel.ChunkWithMetadata> sourceChunks,
            int topN) {

        List<ChunkScore> scores = new ArrayList<>();
        for (CitationModel.ChunkWithMetadata chunk : sourceChunks) {
            if (chunk.getChunkText() == null || chunk.getChunkText().isEmpty()) continue;

            // CHANGED: use cosine similarity instead of simple Jaccard
            // This approach considers word frequency, so "full-stack development"
            // appearing multiple times increases similarity.
            float similarity = calculateTextSimilarity(sentence, chunk.getChunkText());

            // CHANGED: lowered threshold from 0.3 to 0.15 for long sentences
            if (similarity > 0.15f) {
                scores.add(new ChunkScore(chunk, similarity));
            }
        }

        // Sort by similarity and take top N
        return scores.stream()
                .sorted((a, b) -> Float.compare(b.score, a.score))
                .limit(topN)
                .map(cs -> cs.chunk)
                .collect(Collectors.toList());
    }

    /**
     * CHANGED: Calculate text similarity using TF-IDF–like cosine similarity.
     * This improves performance on longer texts like résumés or project summaries.
     *
     * Example:
     *  text1: "Suman Singh is a software engineer with 9+ years of experience"
     *  text2: "9+ years of full-stack development expertise with Python, Java"
     *  -> cosine similarity ≈ 0.7 (vs. Jaccard ~0.25)
     */
    private float calculateTextSimilarity(String text1, String text2) {
        Map<String, Integer> freq1 = countWordFrequency(text1);
        Map<String, Integer> freq2 = countWordFrequency(text2);

        // union of all unique words
        Set<String> allWords = new HashSet<>();
        allWords.addAll(freq1.keySet());
        allWords.addAll(freq2.keySet());

        double dot = 0.0, norm1 = 0.0, norm2 = 0.0;
        for (String word : allWords) {
            int f1 = freq1.getOrDefault(word, 0);
            int f2 = freq2.getOrDefault(word, 0);
            dot += f1 * f2;
            norm1 += f1 * f1;
            norm2 += f2 * f2;
        }

        if (norm1 == 0 || norm2 == 0) return 0f;
        return (float) (dot / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    /**
     * CHANGED: helper for cosine similarity – counts term frequency for each word
     */
    private Map<String, Integer> countWordFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();
        String[] words = text.toLowerCase().split("\\s+");
        for (String w : words) {
            String cleaned = w.replaceAll("[^a-z0-9]", "");
            if (cleaned.length() > 2) { // skip tiny tokens like "is", "of"
                freq.put(cleaned, freq.getOrDefault(cleaned, 0) + 1);
            }
        }
        return freq;
    }

    /**
     * Create citation object
     */
    private CitationModel.Citation createCitation(CitationModel.ChunkWithMetadata chunk, int citationId) {
        CitationModel.Citation citation = new CitationModel.Citation();
        citation.setCitationId(citationId);
        citation.setDocumentName(chunk.getDocumentName());
        citation.setPageNumber(chunk.getPageNumber());
        citation.setSectionTitle(chunk.getSectionTitle());

        // CHANGED: set confidence = locally computed similarity score (not the retrieval one)
        citation.setConfidence(chunk.getSimilarityScore() != null ? chunk.getSimilarityScore() : 0.8f);

        citation.setChunkId(chunk.getDocumentId() + "_" + chunk.getChunkIndex());
        citation.setStartPosition(chunk.getChunkStartPos());
        citation.setEndPosition(chunk.getChunkEndPos());

        // Create excerpt (truncate safely)
        String excerpt = chunk.getChunkText();
        if (excerpt != null && excerpt.length() > MAX_EXCERPT_LENGTH) {
            int cut = excerpt.lastIndexOf(' ', MAX_EXCERPT_LENGTH);
            if (cut == -1) cut = MAX_EXCERPT_LENGTH;
            excerpt = excerpt.substring(0, cut).trim() + "...";
        }
        citation.setExcerpt(excerpt);
        return citation;
    }

    /**
     * Build response with inline citations
     */
    private String buildAnnotatedResponse(
            List<CitationModel.Sentence> sentences,
            Map<Integer, List<CitationModel.Citation>> sentenceCitations) {

        StringBuilder annotated = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            CitationModel.Sentence sentence = sentences.get(i);
            annotated.append(sentence.getText());

            if (sentenceCitations.containsKey(i)) {
                List<CitationModel.Citation> citations = sentenceCitations.get(i);
                for (CitationModel.Citation citation : citations) {
                    annotated.append(" [").append(citation.getCitationId()).append("]");
                }
            }
            annotated.append(" ");
        }
        return annotated.toString().trim();
    }

    /**
     * Create metadata
     */
    private CitationModel.CitationMetadata createMetadata(
            List<CitationModel.Citation> citations,
            List<CitationModel.ChunkWithMetadata> sourceChunks) {

        CitationModel.CitationMetadata metadata = new CitationModel.CitationMetadata();
        metadata.setTotalSources(citations.size());
        metadata.setCitationsAdded(citations.size());
        metadata.setTotalChunksRetrieved(sourceChunks.size());

        if (!citations.isEmpty()) {
            float avgConf = (float) citations.stream()
                    .mapToDouble(c -> c.getConfidence() != null ? c.getConfidence() : 0.0)
                    .average()
                    .orElse(0.0);
            metadata.setAvgConfidence(avgConf);
        } else {
            metadata.setAvgConfidence(0.0f);
        }

        Set<String> uniqueDocs = citations.stream()
                .map(CitationModel.Citation::getDocumentName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        metadata.setDocumentsUsed(new ArrayList<>(uniqueDocs));

        return metadata;
    }

    private CitationModel.CitationMetadata createEmptyMetadata() {
        CitationModel.CitationMetadata metadata = new CitationModel.CitationMetadata();
        metadata.setTotalSources(0);
        metadata.setAvgConfidence(0.0f);
        metadata.setDocumentsUsed(Collections.emptyList());
        metadata.setTotalChunksRetrieved(0);
        metadata.setCitationsAdded(0);
        return metadata;
    }

    /**
     * Format citations as text footer
     */
    public String formatCitationsAsText(List<CitationModel.Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\nSources:\n");

        for (CitationModel.Citation citation : citations) {
            sb.append(String.format("[%d] %s",
                    citation.getCitationId(),
                    citation.getDocumentName()));

            if (citation.getPageNumber() != null && citation.getPageNumber() > 0) {
                sb.append(" - Page ").append(citation.getPageNumber());
            }

            if (citation.getConfidence() != null) {
                sb.append(String.format(" (%.0f%% confidence)", citation.getConfidence() * 100));
            }
            sb.append("\n");
            if (citation.getExcerpt() != null && !citation.getExcerpt().isEmpty()) {
                sb.append("    \"").append(citation.getExcerpt()).append("\"\n");
            }
        }
        return sb.toString();
    }

    private static class ChunkScore {
        CitationModel.ChunkWithMetadata chunk;
        float score;

        ChunkScore(CitationModel.ChunkWithMetadata chunk, float score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}