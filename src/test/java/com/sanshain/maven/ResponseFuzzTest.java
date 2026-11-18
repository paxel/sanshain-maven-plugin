package com.sanshain.maven;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.logging.Log;
import org.mockito.Mockito;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads what the service answers: any body, any version and any {@code Sanshain-Warning}
 * header values.
 *
 * <p>An input is read as the body of a failed call, as a required document, as the version the
 * service names and, split at line feeds, as the values of the warning headers.</p>
 *
 * <p>The inputs in {@code src/test/resources/com/sanshain/maven/ResponseFuzzTestInputs} run
 * with every test run; {@code JAZZER_FUZZ=1 mvn test -Dtest=ResponseFuzzTest} fuzzes.</p>
 */
class ResponseFuzzTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Records nothing, so a long fuzz run does not fill the memory with log calls. */
    private static final Log LOG = Mockito.mock(Log.class, Mockito.withSettings().stubOnly());

    @FuzzTest
    void response(byte[] data) {
        String body = new String(data, StandardCharsets.UTF_8);

        SanshainHttpClient.ErrorBody error = SanshainHttpClient.parseErrorBody(MAPPER, LOG, body);
        assertNotNull(error);
        assertNotNull(error.message(body));
        SanshainHttpClient.namesAnnotationsField(body);
        SanshainHttpClient.namesOriginField(body);
        // A require that asked for a format looks into the document it got.
        SanshainHttpClient.isOpenApiDocument(body);
        if (SanshainHttpClient.pre2Version(body) != null) {
            assertTrue(SanshainHttpClient.isOlderThan240(body), "before 2.0 but not before 2.4.0: " + body);
        }

        // HttpHeaders refuses a value with a character no header line can carry; the JDK's
        // client never hands one over.
        List<String> values = Arrays.asList(body.split("\n", -1));
        HttpHeaders headers;
        try {
            headers = HttpHeaders.of(Map.of("Sanshain-Warning", values), (name, value) -> true);
        } catch (IllegalArgumentException notAHeaderValue) {
            return;
        }
        assertEquals(values.size(), SanshainHttpClient.warningLines(headers).size());
    }
}
