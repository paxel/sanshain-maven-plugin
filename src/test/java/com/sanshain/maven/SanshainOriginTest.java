package com.sanshain.maven;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainOriginTest {

    @Test
    public void testNothingKnownIsNone() {
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, null, null, new HashMap<>());
        assertTrue(origin.isNone());
        Map<String, Object> payload = new LinkedHashMap<>();
        origin.addTo(payload);
        assertTrue(payload.isEmpty());
    }

    @Test
    public void testPropertiesWinOverEnvironmentAndRunner() {
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_BUILD_URL", "https://ci.acme.corp/env/1");
        env.put("SANSHAIN_CHANGE_URL", "https://git.acme.corp/env/pull/1");
        env.put("SANSHAIN_TICKETS", "ENV-1");
        env.put("SANSHAIN_NOTE", "from env");
        env.put("BUILD_URL", "https://jenkins.acme.corp/job/x/7/");
        SanshainOrigin origin = SanshainOrigin.resolve(" https://ci.acme.corp/prop/2 ", "https://git.acme.corp/prop/pull/2",
                "PROP-2, PROP-3 ,PROP-2,", " from property ", env);
        assertEquals("https://ci.acme.corp/prop/2", origin.getBuildUrl());
        assertEquals("https://git.acme.corp/prop/pull/2", origin.getChangeUrl());
        assertEquals(Arrays.asList("PROP-2", "PROP-3"), origin.getTickets());
        assertEquals("from property", origin.getNote());
    }

    @Test
    public void testSanshainVariablesWinOverTheRunner() {
        Map<String, String> env = new HashMap<>();
        env.put("SANSHAIN_BUILD_URL", "https://ci.acme.corp/env/1");
        env.put("BUILD_URL", "https://jenkins.acme.corp/job/x/7/");
        env.put("CHANGE_URL", "https://git.acme.corp/x/pull/9");
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, null, null, env);
        assertEquals("https://ci.acme.corp/env/1", origin.getBuildUrl());
        assertEquals("https://git.acme.corp/x/pull/9", origin.getChangeUrl());
        assertTrue(origin.getTickets().isEmpty());
        assertNull(origin.getNote());
    }

    @Test
    public void testGitHubActionsVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("GITHUB_SERVER_URL", "https://github.com");
        env.put("GITHUB_REPOSITORY", "acme/orders");
        env.put("GITHUB_RUN_ID", "123456");
        env.put("GITHUB_REF", "refs/pull/42/merge");
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, null, null, env);
        assertEquals("https://github.com/acme/orders/actions/runs/123456", origin.getBuildUrl());
        assertEquals("https://github.com/acme/orders/pull/42", origin.getChangeUrl());
        // A push build belongs to no change.
        env.put("GITHUB_REF", "refs/heads/main");
        assertNull(SanshainOrigin.resolve(null, null, null, null, env).getChangeUrl());
    }

    @Test
    public void testGitLabCiVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("CI_JOB_URL", "https://gitlab.acme.corp/acme/orders/-/jobs/777");
        env.put("CI_MERGE_REQUEST_PROJECT_URL", "https://gitlab.acme.corp/acme/orders");
        env.put("CI_MERGE_REQUEST_IID", "12");
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, null, null, env);
        assertEquals("https://gitlab.acme.corp/acme/orders/-/jobs/777", origin.getBuildUrl());
        assertEquals("https://gitlab.acme.corp/acme/orders/-/merge_requests/12", origin.getChangeUrl());
    }

    @Test
    public void testJenkinsVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("BUILD_URL", "https://jenkins.acme.corp/job/orders/7/");
        env.put("CHANGE_URL", "https://bitbucket.acme.corp/projects/ORD/repos/orders/pull-requests/5");
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, null, null, env);
        assertEquals("https://jenkins.acme.corp/job/orders/7/", origin.getBuildUrl());
        assertEquals("https://bitbucket.acme.corp/projects/ORD/repos/orders/pull-requests/5", origin.getChangeUrl());
    }

    @Test
    public void testAddToSendsOnlyWhatIsSet() {
        SanshainOrigin origin = SanshainOrigin.resolve(null, null, "ORD-7", "Ship it", new HashMap<>());
        Map<String, Object> payload = new LinkedHashMap<>();
        origin.addTo(payload);
        assertEquals(2, payload.size());
        assertEquals(Arrays.asList("ORD-7"), payload.get("tickets"));
        assertEquals("Ship it", payload.get("note"));
        assertFalse(payload.containsKey("build_url"));
        assertFalse(payload.containsKey("change_url"));
    }
}
