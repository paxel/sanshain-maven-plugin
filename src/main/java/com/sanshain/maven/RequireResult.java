package com.sanshain.maven;

/**
 * Result of a require operation, containing the content and ETag.
 */
public class RequireResult {

    private final String content;
    private final String etag;
    private final boolean notModified;
    private final String resolution;
    private final String servedBranch;

    private RequireResult(String content, String etag, boolean notModified, String resolution, String servedBranch) {
        this.content = content;
        this.etag = etag;
        this.notModified = notModified;
        this.resolution = resolution;
        this.servedBranch = servedBranch;
    }

    /**
     * Creates a result for a 200 OK response, including the resolution headers.
     * @param content      the response content
     * @param etag         the ETag header value (may be null)
     * @param resolution   the X-Sanshain-Resolution header value (published/inherited, may be null)
     * @param servedBranch the X-Sanshain-Served-Branch header value (may be null)
     * @return the result
     */
    public static RequireResult ok(String content, String etag, String resolution, String servedBranch) {
        return new RequireResult(content, etag, false, resolution, servedBranch);
    }

    /**
     * Creates a result for a 304 Not Modified response.
     * @return the result
     */
    public static RequireResult notModified() {
        return new RequireResult(null, null, true, null, null);
    }

    /** Gets the content. @return the content, or null if not modified */
    public String getContent() { return content; }

    /** Gets the ETag. @return the ETag, or null */
    public String getEtag() { return etag; }

    /** Whether the response was 304 Not Modified. @return true if not modified */
    public boolean isNotModified() { return notModified; }

    /** Gets how the request was resolved. @return "published" or "inherited", or null if not present */
    public String getResolution() { return resolution; }

    /** Gets the branch that actually served the response. @return the served branch, or null if not present */
    public String getServedBranch() { return servedBranch; }
}
