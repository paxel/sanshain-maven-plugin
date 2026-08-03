package com.sanshain.maven;

/**
 * Result of a require operation, containing the content, ETag, and the stability that answered.
 */
public class RequireResult {

    private final String content;
    private final String etag;
    private final boolean notModified;
    private final String stability;

    private RequireResult(String content, String etag, boolean notModified, String stability) {
        this.content = content;
        this.etag = etag;
        this.notModified = notModified;
        this.stability = stability;
    }

    /**
     * Creates a result for a 200 OK response.
     * @param content   the response content
     * @param etag      the ETag header value (may be null)
     * @param stability the X-Sanshain-Stability header value (ga/snapshot — what actually answered, may be null)
     * @return the result
     */
    public static RequireResult ok(String content, String etag, String stability) {
        return new RequireResult(content, etag, false, stability);
    }

    /**
     * Creates a result for a 304 Not Modified response.
     * @return the result
     */
    public static RequireResult notModified() {
        return new RequireResult(null, null, true, null);
    }

    /** Gets the content. @return the content, or null if not modified */
    public String getContent() { return content; }

    /** Gets the ETag. @return the ETag, or null */
    public String getEtag() { return etag; }

    /** Whether the response was 304 Not Modified. @return true if not modified */
    public boolean isNotModified() { return notModified; }

    /** Gets the stability that answered the pin. @return "ga" or "snapshot", or null if not present */
    public String getStability() { return stability; }
}
