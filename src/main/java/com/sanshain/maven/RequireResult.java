package com.sanshain.maven;

/**
 * Result of a require operation, containing the content and ETag.
 */
public class RequireResult {

    private final String content;
    private final String etag;
    private final boolean notModified;

    private RequireResult(String content, String etag, boolean notModified) {
        this.content = content;
        this.etag = etag;
        this.notModified = notModified;
    }

    /**
     * Creates a result for a 200 OK response.
     * @param content the response content
     * @param etag    the ETag header value (may be null)
     * @return the result
     */
    public static RequireResult ok(String content, String etag) {
        return new RequireResult(content, etag, false);
    }

    /**
     * Creates a result for a 304 Not Modified response.
     * @return the result
     */
    public static RequireResult notModified() {
        return new RequireResult(null, null, true);
    }

    /** Gets the content. @return the content, or null if not modified */
    public String getContent() { return content; }

    /** Gets the ETag. @return the ETag, or null */
    public String getEtag() { return etag; }

    /** Whether the response was 304 Not Modified. @return true if not modified */
    public boolean isNotModified() { return notModified; }
}
