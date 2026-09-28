package dev.ultima.review;

import org.junit.jupiter.api.Test;

/** The single JUnit entrypoint behind {@code ./gradlew test} and {@code ./gradlew check}. */
public class CanonicalRegressionTest {
    @Test
    void aggregate() throws Exception {
        MergedRegressionTest.main(new String[0]);
    }
}
