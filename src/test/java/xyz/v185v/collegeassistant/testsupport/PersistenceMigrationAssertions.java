package xyz.v185v.collegeassistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class PersistenceMigrationAssertions {
    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAPPER_XML = Path.of("src/main/resources/mapper");

    private PersistenceMigrationAssertions() {
    }

    public static void assertMapperBacked(Class<?> repositoryType)
            throws IOException {
        Path source = Path.of("src/main/java",
                repositoryType.getName().replace('.', '/') + ".java");
        String repositorySource = Files.readString(source);
        assertThat(repositorySource).doesNotContain("JdbcClient", ".sql(");
        assertThat(repositoryType.getDeclaredFields())
                .anySatisfy(field -> assertThat(field.getType()
                        .isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class)).isTrue());
    }

    public static void assertFinalArchitectureGuards() throws IOException {
        List<String> javaFilesWithJdbc = new ArrayList<>();
        for (Path source : filesUnder(MAIN_JAVA, ".java")) {
            String javaSource = Files.readString(source);
            if (javaSource.contains("JdbcClient") || javaSource.contains(".sql(")) {
                javaFilesWithJdbc.add(portablePath(source));
            }
        }
        assertThat(javaFilesWithJdbc)
                .containsExactlyInAnyOrder(
                        "src/main/java/xyz/v185v/collegeassistant/config/JdbcConfig.java",
                        "src/main/java/xyz/v185v/collegeassistant/ai/config/EmbeddingPartitionRepository.java");

        List<Path> mapperFiles = filesUnder(MAPPER_XML, ".xml");
        assertThat(mapperFiles).isNotEmpty();
        for (Path mapper : mapperFiles) {
            assertThat(Files.readString(mapper))
                    .as("Mapper XML %s", portablePath(mapper))
                    .doesNotContain("${", "<cache");
        }
    }

    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    private static String portablePath(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
