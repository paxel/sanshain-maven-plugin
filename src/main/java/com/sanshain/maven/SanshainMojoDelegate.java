package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import java.util.Map;

/**
 * Shared logic for Sanshain Mojos using composition. Centralizes the resolution of URL, token,
 * service name, compression, combine, best-effort, and stability so both Mojos behave the same.
 */
public class SanshainMojoDelegate {

    private final Log log;
    private final Settings settings;
    private final String serverId;
    private final boolean strict;
    private Map<String, String> environmentVariables = System.getenv();

    public SanshainMojoDelegate(Log log, Settings settings, String serverId, boolean strict) {
        this.log = log;
        this.settings = settings;
        this.serverId = serverId != null ? serverId : "sanshain";
        this.strict = strict;
    }

    public void abort(String message, boolean bestEffort) throws MojoExecutionException {
        if (bestEffort) {
            log.warn("Sanshain goal skipped (best effort): " + message);
            return;
        }
        if (strict) {
            throw new MojoExecutionException(message);
        }
        log.warn(message + " Skipping. Set sanshain.strict=true to fail in this case.");
    }

    public String resolveUrl(String sanshainUrl, SanshainConfig config) {
        if (sanshainUrl != null) return sanshainUrl;

        String settingsUrl = resolveUrlFromSettings();
        if (settingsUrl != null) return settingsUrl;

        if (config.getSanshainUrl() != null) return config.getSanshainUrl();

        return "http://localhost:8080";
    }

    private String resolveUrlFromSettings() {
        if (settings == null) return null;
        Server server = settings.getServer(serverId);
        if (server == null) return null;
        String url = getServerConfigProperty(server, "url");
        if (url == null) {
            url = getServerConfigProperty(server, "sanshainUrl");
        }
        return url;
    }

    public String resolveToken(String token) {
        String envToken = environmentVariables.get("SANSHAIN_TOKEN");
        if (envToken != null) return envToken;

        if (settings != null) {
            Server server = settings.getServer(serverId);
            if (server != null && server.getPassword() != null) {
                return server.getPassword();
            }
        }

        return token;
    }

    public boolean resolveCompression(Boolean compression, SanshainConfig config) {
        if (compression != null) return compression;
        if (config.getCompression() != null) return config.getCompression();
        return true;
    }

    public boolean resolveInsecure(Boolean insecure, SanshainConfig config) {
        if (insecure != null) return insecure;
        if (config.getInsecure() != null) return config.getInsecure();
        return false;
    }

    public boolean resolveBestEffort(Boolean bestEffort, SanshainConfig config) {
        if (bestEffort != null) return bestEffort;
        String envBestEffort = environmentVariables.get("SANSHAIN_BEST_EFFORT");
        if (envBestEffort != null) return Boolean.parseBoolean(envBestEffort);
        if (config != null && config.getBestEffort() != null) return config.getBestEffort();
        return false;
    }

    public boolean resolveCombine(Boolean combine, SanshainConfig config) {
        if (combine != null) return combine;
        String envCombine = environmentVariables.get("SANSHAIN_COMBINE");
        if (envCombine != null) return Boolean.parseBoolean(envCombine);
        if (config != null && config.getCombine() != null) return config.getCombine();
        return true;
    }

    public boolean resolveCombine(Boolean itemCombine, Boolean mojoCombine, SanshainConfig config) {
        if (itemCombine != null) return itemCombine;
        return resolveCombine(mojoCombine, config);
    }

    /**
     * Resolves the stability declared on every Provide. Always {@code snapshot} unless the
     * explicit GA switch is set: the {@code -Dsanshain.ga=true} Maven property or the
     * {@code SANSHAIN_GA=true} environment variable. There is no git magic and no stability
     * field in sanshain.yaml — CI sets the switch on release pipelines, and that is the whole
     * mechanism.
     *
     * @param ga the value of the {@code sanshain.ga} Maven property
     * @return {@code "ga"} if the switch is set, otherwise {@code "snapshot"}
     */
    public String resolveStability(boolean ga) {
        if (ga) return "ga";
        String envGa = environmentVariables.get("SANSHAIN_GA");
        if (envGa != null && Boolean.parseBoolean(envGa)) return "ga";
        return "snapshot";
    }

    /**
     * Resolves which graph this build speaks for, on the same terms as stability:
     * a Maven property or an environment variable, set by the pipeline, absent
     * from sanshain.yaml. Trunk CI declares {@code trunk}; a release or hotfix
     * pipeline declares the sanshain-branch it builds for.
     *
     * <p>Nothing is inferred from the git branch. A checkout is the same on a
     * developer's laptop and on the trunk runner, and the trunk pin store is
     * last-writer-wins — guessing would let a local build quietly overwrite CI.
     *
     * @param trunk the {@code sanshain.trunk} Maven property, or null if unset
     * @param tag   the {@code sanshain.tag} Maven property, or null if unset
     * @return the resolved stream
     * @throws MojoExecutionException if the build declares both trunk and a tag
     */
    public SanshainStream resolveStream(Boolean trunk, String tag) throws MojoExecutionException {
        try {
            return SanshainStream.resolve(trunk, tag,
                    environmentVariables.get("SANSHAIN_TRUNK"),
                    environmentVariables.get("SANSHAIN_TAG"));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    public void handleException(Exception e, boolean bestEffort) throws MojoExecutionException {
        if (bestEffort) {
            log.warn("Sanshain goal failed (best effort): " + e.getMessage());
        } else if (e instanceof MojoExecutionException) {
            throw (MojoExecutionException) e;
        } else {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    void setEnvironmentVariables(Map<String, String> environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public String resolveServiceName(String serviceName, SanshainConfig config) {
        if (serviceName != null) return serviceName;
        String name = config.getServiceName();
        if (name == null) {
            name = config.getClientName();
        }
        return name;
    }

    private String getServerConfigProperty(Server server, String property) {
        if (server == null || server.getConfiguration() == null) return null;
        Object config = server.getConfiguration();
        if (config instanceof org.codehaus.plexus.util.xml.Xpp3Dom) {
            org.codehaus.plexus.util.xml.Xpp3Dom dom = (org.codehaus.plexus.util.xml.Xpp3Dom) config;
            org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild(property);
            if (child != null) return child.getValue();
        }
        return null;
    }
}
