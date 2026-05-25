package com.saffron.cashflow.integration.dotykacka;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test for the Dotykačka filter encoding bug — the API uses
 * literal "|" and ";" in its filter syntax, which Java's URI parser rejects
 * unless percent-encoded. Earlier we only encoded the timestamp inside the
 * filter, which left the syntax characters raw and produced
 * {@code IllegalArgumentException: Illegal character in query at index 71}.
 */
class DotykackaClientUrlEncodingTest {

    @Test
    void filterEncodesPipesAndSemicolons() {
        Instant cursor = Instant.parse("2026-05-18T17:19:19.757493497Z");
        String raw = "documentType|eq|RECEIPT;versionDate|gte|" + cursor;
        String encoded = DotykackaClient.enc(raw);

        assertThat(encoded).doesNotContain("|", ";");
        assertThat(encoded).contains("%7C", "%3B");

        // The full URL Dotykačka builds in DotykackaSyncService should parse
        // cleanly once the whole filter is encoded.
        String url = "https://api.dotykacka.cz/v2/clouds/363814068/orders"
                + "?filter=" + encoded
                + "&include=orderItems&sort=versionDate&limit=100&page=1";
        assertThatCode(() -> URI.create(url)).doesNotThrowAnyException();
    }
}
