package com.scamera.app.util;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P638: the build runs the unit tests its workflow lists by name (.github/workflows/build-test-apk.yml), so a test class left out
 * of the list never runs. Thirty were - one of them (CaNetPTest) had gone stale behind a settings regrouping without anyone
 * seeing it. Every *Test class under src/test with a test method is on the list.
 */
public class WorkflowTestListTest {
    private static final Path TESTS = Paths.get("src/test/java");
    private static final Path WORKFLOW = Paths.get("../.github/workflows/build-test-apk.yml");

    /** The class names and wildcard prefixes after --tests in the workflow's unit-test command. */
    static Set<String> listed(String workflow) {
        final Set<String> out = new HashSet<>();
        for (String line : workflow.split("\n")) {
            if (!line.contains(":app:testDebugUnitTest")) continue;
            final Matcher m = Pattern.compile("--tests \"([^\"]+)\"").matcher(line);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    static boolean covered(String cls, Set<String> listed) {
        if (listed.contains(cls)) return true;
        for (String l : listed) if (l.endsWith("*") && cls.startsWith(l.substring(0, l.length() - 1))) return true;
        return false;
    }

    @Test public void everyTestClassIsInTheWorkflowsList() throws IOException {
        final Set<String> listed = listed(new String(Files.readAllBytes(WORKFLOW), StandardCharsets.UTF_8));
        assertTrue("the workflow's unit-test command was not found", listed.size() > 100);
        final List<String> missing = new ArrayList<>();
        final List<Path> files;
        try (Stream<Path> s = Files.walk(TESTS)) {
            files = s.filter(p -> p.getFileName().toString().endsWith("Test.java")).collect(Collectors.toList());
        }
        for (Path f : files) {
            final String source = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            if (!source.contains("@Test")) continue;
            final String rel = TESTS.relativize(f).toString().replace('\\', '/');
            final String cls = rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
            if (!covered(cls, listed)) missing.add(cls);
        }
        assertTrue("test classes the build never runs (add them to the --tests list of build-test-apk.yml): " + missing, missing.isEmpty());
    }

    @Test public void aWildcardCoversItsPackage() {
        final Set<String> listed = listed("  ./gradlew :app:testDebugUnitTest --tests \"a.b.CTest\" --tests \"a.portrait.*\" x\n");
        assertTrue(covered("a.b.CTest", listed));
        assertTrue(covered("a.portrait.MaskTest", listed));
        assertTrue(!covered("a.b.DTest", listed));
    }
}
