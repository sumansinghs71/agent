package com.chatbot.agent.model;

public class ChunkWithMetadata {
    public String chunkText;
    public String documentName;
    public Integer pageNumber;
    public String sectionTitle;
    public Integer chunkIndex;
    public Integer chunkStartPos;
    public Integer chunkEndPos;

    public ChunkWithMetadata() {}

    public ChunkWithMetadata(String chunkText, String documentName, Integer pageNumber, String sectionTitle, Integer chunkIndex, Integer chunkStartPos, Integer chunkEndPos) {
        this.chunkText = chunkText;
        this.documentName = documentName;
        this.pageNumber = pageNumber;
        this.sectionTitle = sectionTitle;
        this.chunkIndex = chunkIndex;
        this.chunkStartPos = chunkStartPos;
        this.chunkEndPos = chunkEndPos;
    }

    // getters and setters
    public String getChunkText() { return chunkText; }
    public void setChunkText(String chunkText) { this.chunkText = chunkText; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getSectionTitle() { return sectionTitle; }
    public void setSectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getChunkStartPos() { return chunkStartPos; }
    public void setChunkStartPos(Integer chunkStartPos) { this.chunkStartPos = chunkStartPos; }
    public Integer getChunkEndPos() { return chunkEndPos; }
    public void setChunkEndPos(Integer chunkEndPos) { this.chunkEndPos = chunkEndPos; }
}

