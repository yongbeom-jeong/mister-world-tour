package com.misterworldtour.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 의존 방향 검사: Data Access → 업무 서브시스템 한 방향만 허용한다.
 * 업무 서브시스템과 UI 는 저장 인터페이스(MemberDataAccessAPI 등)를 자기 패키지에 가지고, dataaccess 패키지를 참조하지 않는다.
 */
class DataAccessDependencyTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/misterworldtour");
    private static final List<String> NON_DATA_ACCESS_PACKAGES =
            List.of("members", "travel", "souvenir", "sms", "speech", "ui", "common");

    @Test
    void businessSubsystemsDoNotDependOnDataAccess() throws IOException {
        for (String pkg : NON_DATA_ACCESS_PACKAGES) {
            try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve(pkg))) {
                List<Path> offenders = files
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(DataAccessDependencyTest::referencesDataAccess)
                        .toList();
                assertThat(offenders).as("%s 패키지가 dataaccess 를 참조함", pkg).isEmpty();
            }
        }
    }

    private static boolean referencesDataAccess(Path file) {
        try {
            return Files.readString(file).contains("com.misterworldtour.dataaccess");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
