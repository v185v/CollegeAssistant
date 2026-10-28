package xyz.v185v.collegeassistant.ugc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import tools.jackson.databind.ObjectMapper;
import xyz.v185v.collegeassistant.ugc.persistence.UgcMapper;

class UgcRepositoryReconciliationSqlTest {

    private static final String MAPPER_RESOURCE = "/mapper/ugc/UgcMapper.xml";

    @Test
    void mapperContainsRequiredStatementsAndStrictReconciliationQuery() throws Exception {
        MapperXml mapper = readMapperXml();

        assertThat(mapper.namespace())
                .isEqualTo("xyz.v185v.collegeassistant.ugc.persistence.UgcMapper");
        assertThat(mapper.statements()).containsKeys(
                "createReturningPost",
                "countPosts",
                "findPosts",
                "findPostById",
                "removeReturningPost",
                "findApprovedById",
                "ensureLike",
                "ensureReport",
                "countReports",
                "findReports",
                "findReport",
                "resolveReportReturning",
                "lockIfApproved",
                "findApprovedForEmbeddingRebuild",
                "lockApprovedForEmbeddingRebuild",
                "findApprovedWithoutChunksAfter",
                "reviewReturningPost");

        assertThat(mapper.statement("findApprovedWithoutChunksAfter"))
                .containsPattern("p\\.status\\s*=\\s*'APPROVED'")
                .containsPattern("p\\.id\\s*>\\s*#\\{afterId}")
                .contains("NOT EXISTS")
                .containsPattern("c\\.doc_ref_type\\s*=\\s*'UGC'")
                .containsPattern("c\\.source_type\\s*=\\s*'UGC'")
                .containsPattern("LIMIT\\s+#\\{limit}");
    }

    @Test
    void invalidCursorOrLimitIsRejectedBeforeMapperAccess() {
        UgcMapper mapper = mock(UgcMapper.class);
        UgcRepository repository = new UgcRepository(mapper, mock(ObjectMapper.class));

        assertThatThrownBy(() -> repository.findApprovedWithoutChunksAfter(-1L, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> repository.findApprovedWithoutChunksAfter(0L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        verifyNoInteractions(mapper);
    }

    private MapperXml readMapperXml() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities", false);

        try (InputStream resource = Objects.requireNonNull(
                getClass().getResourceAsStream(MAPPER_RESOURCE),
                "Missing mapper resource " + MAPPER_RESOURCE)) {
            Document document = factory.newDocumentBuilder().parse(resource);
            Element mapper = document.getDocumentElement();
            Map<String, String> statements = new LinkedHashMap<>();
            NodeList selects = mapper.getElementsByTagName("select");
            for (int index = 0; index < selects.getLength(); index++) {
                Element select = (Element) selects.item(index);
                statements.put(
                        select.getAttribute("id"),
                        select.getTextContent().replaceAll("\\s+", " ").trim());
            }
            return new MapperXml(mapper.getAttribute("namespace"), statements);
        }
    }

    private record MapperXml(String namespace, Map<String, String> statements) {
        private String statement(String id) {
            return Objects.requireNonNull(statements.get(id), "Missing statement " + id);
        }
    }
}
