package com.misterworldtour;

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
 *   <li>Data Access → 업무 서브시스템 한 방향만: 업무·ui 는 dataaccess 를 참조하지 않는다
 *       (저장 인터페이스는 쓰는 쪽이 가진다).</li>
 * </ul>
 * 소스는 모든 모듈(mwt-*)에서 읽는다. 테스트는 mwt-app 폴더에서 실행된다.
 */
class PackageDependencyTest {

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
        for (String pkg : List.of("members", "travel", "souvenir", "sms", "speech", "ui")) {
            assertThat(graph.getOrDefault(pkg, Set.of())).as("%s 패키지의 의존", pkg).doesNotContain("dataaccess");
        }
    }

    /** 다른 서브시스템은 *API 로만 부른다: 다른 패키지의 *Manager 클래스를 참조하지 않는다 (데모 데이터 demo 는 제외). */
    @Test
    void otherSubsystemsUseApisNotManagers() throws IOException {
        Pattern managerRef = Pattern.compile("com\\.misterworldtour\\.(\\w+)\\.(\\w+Manager)\\b");
        List<String> offenders = new ArrayList<>();
        for (Path dir : packageDirs()) {
            String pkg = dir.getFileName().toString();
            if (pkg.equals("demo")) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Matcher m = managerRef.matcher(COMMENTS.matcher(read(file)).replaceAll(""));
                    while (m.find()) {
                        if (!m.group(1).equals(pkg)) {
                            offenders.add(file.getFileName() + " → " + m.group(1) + "." + m.group(2));
                        }
                    }
                }
            }
        }
        assertThat(offenders).as("다른 서브시스템의 *Manager 직접 사용").isEmpty();
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
        for (Path dir : packageDirs()) {
            String pkg = dir.getFileName().toString();
            Set<String> deps = graph.computeIfAbsent(pkg, k -> new TreeSet<>());
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
        }
        return graph;
    }

    /** 모든 모듈(mwt-*, 계층 폴더 안의 패키지 모듈 포함)의 com/misterworldtour 아래 패키지 폴더. */
    private static List<Path> packageDirs() throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<Path> modules = new ArrayList<>(mwtDirs(Path.of("..")));
        for (Path layer : List.copyOf(modules)) {
            modules.addAll(mwtDirs(layer));
        }
        for (Path module : modules) {
            Path root = module.resolve("src/main/java/com/misterworldtour");
            if (Files.isDirectory(root)) {
                try (Stream<Path> packages = Files.list(root)) {
                    dirs.addAll(packages.filter(Files::isDirectory).toList());
                }
            }
        }
        assertThat(dirs).as("모듈의 패키지 폴더").hasSizeGreaterThan(5);
        return dirs;
    }

    private static List<Path> mwtDirs(Path parent) throws IOException {
        try (Stream<Path> children = Files.list(parent)) {
            return children.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("mwt-")).toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
