package tools.jackson.failing.async;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.assertj.core.api.Assertions;
import org.junit.Test;

import tools.jackson.core.*;
import tools.jackson.core.async.ByteArrayFeeder;
import tools.jackson.core.filter.FilteringParserDelegate;
import tools.jackson.core.filter.JsonPointerBasedFilter;
import tools.jackson.core.filter.TokenFilter;
import tools.jackson.core.json.JsonFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FilteringOnAsyncInfiniteLoop1144Test
{
    private final JsonFactory JSON_F = new JsonFactory();

    private final byte[] DOC = "{\"first\":1,\"second\":2}"
            .getBytes(StandardCharsets.UTF_8);

    // Just to show expected filtering behavior with blocking alternative
    @Test
    public void testFilteringBlockingParser() throws Exception
    {
        try (JsonParser p = JSON_F.createParser(ObjectReadContext.empty(), DOC)) {
            try (JsonParser fp = new FilteringParserDelegate(p,
                new JsonPointerBasedFilter("/second"),
                    TokenFilter.Inclusion.ONLY_INCLUDE_ALL, false)) {
                assertEquals(JsonToken.VALUE_NUMBER_INT, fp.nextToken());
                assertEquals(2, fp.getIntValue());
                assertNull(fp.nextToken());
            }
        }
    }

    // And here's reproduction of infinite loop
    @SuppressWarnings("resource")
    @Test
    public void testFilteringNonBlockingParser() throws Exception
    {
        JsonParser nonBlockingParser = JSON_F.createNonBlockingByteArrayParser(ObjectReadContext.empty());
        ByteArrayFeeder inputFeeder = (ByteArrayFeeder) nonBlockingParser.nonBlockingInputFeeder();

        // If we did this, things would work:
        /*
        inputFeeder.feedInput(DOC, 0, DOC.length);
        inputFeeder.endOfInput();
         */

        JsonParser filteringParser = new FilteringParserDelegate(nonBlockingParser,
                new JsonPointerBasedFilter("/second"),
                    TokenFilter.Inclusion.ONLY_INCLUDE_ALL, false);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.schedule(() -> {
            try {
                // This doesn't seem to make a difference:
                inputFeeder.feedInput(DOC, 0, DOC.length);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 500, TimeUnit.MILLISECONDS);
        
        Future<JsonToken> future = Executors.newSingleThreadExecutor().submit(() -> filteringParser.nextToken());
        Assertions.assertThat(future)
                .succeedsWithin(Duration.ofSeconds(5))
                .isNotNull()
                .isNotEqualTo(JsonToken.NOT_AVAILABLE);
    }
}
