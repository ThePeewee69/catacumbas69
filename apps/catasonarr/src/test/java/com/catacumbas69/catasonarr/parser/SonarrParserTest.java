package com.catacumbas69.catasonarr.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs Sonarr 4.0.20's parser tests on Catasonarr's translation (orch.md decision 122): every {@code [TestCase]} of
 * Sonarr's {@code ParserTests}, one dynamic test each, checked as Sonarr's test method checks it
 * ({@link SonarrParserCases}).
 */
class SonarrParserTest {

    private static Map<String, Map<String, List<List<Object>>>> cases() throws IOException {
        try (InputStream in = SonarrParserTest.class.getResourceAsStream("/sonarr/parser-cases.json")) {
            return new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
    }

    @TestFactory
    Stream<DynamicTest> sonarrsParserTests() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        cases().forEach((fixture, methods) -> methods.forEach((method, all) -> {
            for (int i = 0; i < all.size(); i++) {
                List<Object> args = all.get(i);
                String name = fixture + "." + method + "[" + i + "] " + args.get(0);
                tests.add(DynamicTest.dynamicTest(name, () -> SonarrParserCases.check(fixture, method, args)));
            }
        }));
        return tests.stream();
    }

    @Test
    void everySonarrTestMethod_hasItsCheck() throws IOException {
        cases().forEach((fixture, methods) -> methods.keySet().forEach(method ->
                assertThat(SonarrParserCases.has(fixture, method)).as(fixture + "." + method).isTrue()));
    }
}
