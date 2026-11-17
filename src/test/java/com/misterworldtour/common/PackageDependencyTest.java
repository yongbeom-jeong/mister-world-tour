package com.misterworldtour.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패키지(서브시스템) 의존 방향 검사. 소스의 {@code com.misterworldtour.<패키지>} 참조(주석 제외)로 의존 그래프를 만든다.
 * <ul>
 *   <li>패키지 사이에 순환 의존이 없어야 한다.</li>
 *   <li>Data Access → 업무 서브시스템 한 방향만: 업무·ui·common 은 dataaccess 를 참조하지 않는다
 *       (저장 인터페이스는 쓰는 쪽이 가진다).</li>
 * </ul>
 */
class PackageDependencyTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/misterworldtour");
    private static final Pattern REFERENCE = Pattern.compile("com\\.misterworldtour\\.(\\w+)\\.");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    void packagesHaveNoDependencyCycles() throws IOException {
        Map<String, Set<String>> graph = dependencyGraph();
        List<String> cycles = new ArrayList<>();
        for (String start : graph.keySet()) {
            findCycle(graph, start, start, new ArrayList<>(List.of(start)), cycles);
        }
        assertThat(cycles).as("패키지 순환 의존").isEmpty();
    }

    @Test
    void onlyDataAccessKnowsDataAccess() throws IOException {
        Map<String, Set<String>> graph = dependencyGraph();
        for (String pkg : List.of("members", "travel", "souvenir", "sms", "speech", "ui", "common")) {
            assertThat(graph.getOrDefault(pkg, Set.of())).as("%s 패키지의 의존", pkg).doesNotContain("dataaccess");
        }
    }

    /** start 로 돌아오는 경로를 찾으면 "a → b → a" 형태로 기록한다 (같은 순환은 가장 작은 패키지에서 시작한 것만). */
    private static void findCycle(Map<String, Set<String>> graph, String start, String current, List<String> path,
                                  List<String> cycles) {
        for (String next : graph.getOrDefault(current, Set.of())) {
            if (next.equals(start)) {
                cycles.add(String.join(" → ", path) + " → " + start);
            } else if (!path.contains(next) && next.compareTo(start) > 0) {
                path.add(next);
                findCycle(graph, start, next, path, cycles);
                path.remove(path.size() - 1);
            }
        }
    }

    private static Map<String, Set<String>> dependencyGraph() throws IOException {
        Map<String, Set<String>> graph = new TreeMap<>();
        try (Stream<Path> packages = Files.list(SOURCE_ROOT)) {
            for (Path dir : packages.filter(Files::isDirectory).toList()) {
                String pkg = dir.getFileName().toString();
                Set<String> deps = new TreeSet<>();
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                        Matcher m = REFERENCE.matcher(COMMENTS.matcher(read(file)).replaceAll(""));
                        while (m.find()) {
                            if (!m.group(1).equals(pkg)) {
                                deps.add(m.group(1));
                            }
                        }
                    });
                }
                graph.put(pkg, deps);
            }
        }
        return graph;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
