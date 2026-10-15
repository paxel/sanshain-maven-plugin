package com.sanshain.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared logic for Sanshain Mojos using composition. Centralizes the resolution of URL, token,
 * service name, compression, combine, best-effort, and stability so both Mojos behave the same.
 */
public class SanshainMojoDelegate {

    private final Log log;
    private final Settings settings;
    private final String serverId;
    /** The {@code sanshain.strict} property, or null if the build did not set it. */
    private final Boolean strictProperty;
    /** The resolved strict mode: the property if set, else sanshain.yaml's {@code strict}, else off. */
    private boolean strict;
    private Map<String, String> environmentVariables = System.getenv();

    public SanshainMojoDelegate(Log log, Settings settings, String serverId, Boolean strict) {
        this.log = log;
        this.settings = settings;
        this.serverId = serverId != null ? serverId : "sanshain";
        this.strictProperty = strict;
        this.strict = strict != null && strict;
    }

    /**
     * Resolves strict mode once sanshain.yaml is read: an explicitly set {@code sanshain.strict}
     * (true or false) wins; otherwise the yaml {@code strict} field decides; otherwise off.
     *
     * @param config the loaded configuration
     */
    public void resolveStrict(SanshainConfig config) {
        if (strictProperty == null && config != null && config.getStrict() != null) {
            strict = config.getStrict();
        }
    }

    /**
     * Whether this execution runs in strict mode (see {@link #resolveStrict(SanshainConfig)}).
     * @return true if strict
     */
    public boolean isStrict() {
        return strict;
    }

    /**
     * Reads a {@code SANSHAIN_*} environment switch the same way in every Sanshain client:
     * trimmed, case-insensitive {@code true} or {@code 1} means on; anything else, or unset, is off.
     *
     * @param value the raw environment value, or null if unset
     * @return true if the switch is on
     */
    static boolean isSwitchOn(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    /**
     * Names the fields of sanshain.yaml no Sanshain client knows, with a puzzled cat, once per
     * execution. They are ignored; under strict the build fails instead. Best-effort does not
     * soften this — it is a configuration error, not a failed call.
     *
     * @param unknown the unknown field paths, in file order
     * @throws MojoFailureException under strict, if there is any unknown field
     */
    public void reportUnknownFields(List<String> unknown) throws MojoFailureException {
        if (unknown == null || unknown.isEmpty()) {
            return;
        }
        String message = "sanshain.yaml: unknown field" + (unknown.size() == 1 ? " " : "s ")
                + unknown.stream().map(f -> "'" + f + "'").collect(Collectors.joining(", "))
                + " - " + (strict ? "rejected (strict)" : "ignored");
        log.warn("   /\\_/\\  ?");
        log.warn("  ( O_o )   " + message);
        log.warn(" ---U-U---");
        if (strict) {
            throw new MojoFailureException(message);
        }
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
        if (envBestEffort != null) return isSwitchOn(envBestEffort);
        if (config != null && config.getBestEffort() != null) return config.getBestEffort();
        return false;
    }

    public boolean resolveCombine(Boolean combine, SanshainConfig config) {
        if (combine != null) return combine;
        String envCombine = environmentVariables.get("SANSHAIN_COMBINE");
        if (envCombine != null) return isSwitchOn(envCombine);
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
        if (isSwitchOn(envGa)) return "ga";
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
     * <p>A build declaring both trunk and a tag is recorded in neither graph: this
     * warns and sends neither, or under strict fails before any request. Best-effort
     * does not soften that.
     *
     * @param trunk the {@code sanshain.trunk} Maven property, or null if unset
     * @param tag   the {@code sanshain.tag} Maven property, or null if unset
     * @return the resolved stream
     * @throws MojoFailureException under strict, if the build declares both trunk and a tag
     */
    public SanshainStream resolveStream(Boolean trunk, String tag) throws MojoFailureException {
        SanshainStream stream = SanshainStream.resolve(trunk, tag,
                environmentVariables.get("SANSHAIN_TRUNK"),
                environmentVariables.get("SANSHAIN_TAG"));
        String warning = stream.getWarning();
        if (warning != null) {
            log.warn(warning);
            if (strict) {
                throw new MojoFailureException("strict: " + warning);
            }
        }
        return stream;
    }

    /**
     * Applies strict mode to the warnings Sanshain sent with a call (already printed by the
     * client as they arrived). Without strict, nothing happens. Under strict, any warning fails
     * the goal with the first one — best-effort does not soften this. A call that also failed
     * has its error printed first, since the strict failure replaces it.
     *
     * @param warnings  the warnings the call carried, in order
     * @param callError the call's own failure, or null if it succeeded
     * @throws MojoFailureException under strict, if there is any warning
     */
    public void failOnWarnings(List<String> warnings, Exception callError) throws MojoFailureException {
        if (!strict || warnings.isEmpty()) {
            return;
        }
        if (callError != null) {
            log.error(callError.getMessage());
        }
        throw new MojoFailureException("strict: Sanshain warned: " + warnings.get(0));
    }

    /**
     * Runs one call to Sanshain and applies {@link #failOnWarnings(List, Exception)} to the
     * warnings it carried, whether it succeeded or failed.
     *
     * @param client the client making the call
     * @param call   the call
     * @param <T>    the call's result type
     * @return the call's result
     * @throws MojoExecutionException if the call failed and strict did not take over
     * @throws MojoFailureException   under strict, if the call carried any warning
     * @throws IOException            if the call failed reading local files
     */
    public <T> T checked(SanshainHttpClient client, SanshainCall<T> call)
            throws MojoExecutionException, MojoFailureException, IOException {
        T result;
        try {
            result = call.call();
        } catch (MojoExecutionException | IOException | RuntimeException e) {
            failOnWarnings(client.takeWarnings(), e);
            throw e;
        }
        failOnWarnings(client.takeWarnings(), null);
        return result;
    }

    /**
     * One call to Sanshain, as {@link #checked(SanshainHttpClient, SanshainCall)} runs it.
     * @param <T> the call's result type
     */
    @FunctionalInterface
    public interface SanshainCall<T> {
        /**
         * Makes the call.
         * @return the call's result
         * @throws MojoExecutionException if the call fails
         * @throws IOException            if reading local files fails
         */
        T call() throws MojoExecutionException, IOException;
    }

    public void handleException(Exception e, boolean bestEffort) throws MojoExecutionException, MojoFailureException {
        if (e instanceof MojoFailureException) {
            // A strict failure: best-effort never softens it.
            throw (MojoFailureException) e;
        }
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
