package com.sanshain.maven;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SanshainStreamTest {

    @Test
    public void testNoneDeclaresNothing() {
        SanshainStream stream = SanshainStream.none();
        assertTrue(stream.isNone());
        assertFalse(stream.isTrunk());
        assertNull(stream.getTag());
        assertEquals("", stream.toQueryParams());
    }

    @Test
    public void testTrunkRendersAsQueryParam() {
        SanshainStream stream = SanshainStream.trunk();
        assertTrue(stream.isTrunk());
        assertNull(stream.getTag());
        assertEquals("&trunk=true", stream.toQueryParams());
    }

    @Test
    public void testTagIsUrlEncoded() {
        SanshainStream stream = SanshainStream.tag("Release Maribou");
        assertFalse(stream.isTrunk());
        assertEquals("Release Maribou", stream.getTag());
        assertEquals("&tag=Release+Maribou", stream.toQueryParams());
    }

    @Test
    public void testBlankTagIsNoStream() {
        assertTrue(SanshainStream.tag("").isNone());
        assertTrue(SanshainStream.tag("   ").isNone());
        assertTrue(SanshainStream.tag(null).isNone());
    }

    /** An unset pipeline is the common case and must stay the quiet one. */
    @Test
    public void testResolveDefaultsToNoStream() {
        assertTrue(SanshainStream.resolve(null, null, null, null).isNone());
    }

    @Test
    public void testResolvePrefersPropertyOverEnvironment() {
        SanshainStream stream = SanshainStream.resolve(null, "from-property", null, "from-env");
        assertEquals("from-property", stream.getTag());
    }

    @Test
    public void testResolveReadsEnvironmentWhenPropertyUnset() {
        assertTrue(SanshainStream.resolve(null, null, "true", null).isTrunk());
        assertEquals("R", SanshainStream.resolve(null, null, null, "R").getTag());
    }

    /**
     * The server answers 400 for a request carrying both. Catching it locally
     * means the pipeline is told what it misconfigured rather than which status
     * code came back.
     */
    @Test
    public void testResolveRefusesTrunkAndTagTogether() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SanshainStream.resolve(true, "R", null, null));
        assertTrue(e.getMessage().contains("never both"), e.getMessage());
        assertTrue(e.getMessage().contains("R"), "the message names the tag, got: " + e.getMessage());
    }

    /** A property-set trunk and an environment tag collide the same way. */
    @Test
    public void testResolveRefusesTrunkPropertyWithTagEnvironment() {
        assertThrows(IllegalArgumentException.class,
                () -> SanshainStream.resolve(true, null, null, "R"));
    }

    /**
     * An explicit {@code -Dsanshain.trunk=false} overrides an inherited
     * {@code SANSHAIN_TRUNK=true}, so one job in a trunk pipeline can opt out
     * without unsetting the environment for everything.
     */
    @Test
    public void testExplicitFalsePropertyBeatsEnvironment() {
        assertTrue(SanshainStream.resolve(false, null, "true", null).isNone());
    }
}
