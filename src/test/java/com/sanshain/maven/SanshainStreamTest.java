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

    /** A build declaring both is recorded in neither graph, with a warning. */
    @Test
    public void testResolveTrunkAndTagTogetherSendsNeither() {
        SanshainStream stream = SanshainStream.resolve(true, "R", null, null);
        assertTrue(stream.isNone());
        assertFalse(stream.isTrunk());
        assertNull(stream.getTag());
        assertEquals("", stream.toQueryParams());
        assertEquals("trunk and tag are mutually exclusive - this build is recorded in neither", stream.getWarning());
    }

    /** A property-set trunk and an environment tag collide the same way. */
    @Test
    public void testResolveTrunkPropertyWithTagEnvironmentSendsNeither() {
        SanshainStream stream = SanshainStream.resolve(true, null, null, "R");
        assertTrue(stream.isNone());
        assertEquals(SanshainStream.MUTUALLY_EXCLUSIVE, stream.getWarning());
    }

    @Test
    public void testOrdinaryStreamsCarryNoWarning() {
        assertNull(SanshainStream.resolve(null, null, null, null).getWarning());
        assertNull(SanshainStream.resolve(true, null, null, null).getWarning());
        assertNull(SanshainStream.resolve(null, "R", null, null).getWarning());
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
