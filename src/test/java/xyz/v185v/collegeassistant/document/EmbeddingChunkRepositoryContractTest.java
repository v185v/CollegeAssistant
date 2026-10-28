package xyz.v185v.collegeassistant.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class EmbeddingChunkRepositoryContractTest {

    private static final String MAPPER_RESOURCE = "/mapper/document/EmbeddingChunkMapper.xml";

    @Test
    void searchDeclaresAReadOnlyTransaction() throws NoSuchMethodException {
        Method search = EmbeddingChunkRepository.class.getMethod(
                "search", EmbeddingChunkSearchCriteria.class);

        Transactional transactional = search.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void mapperXmlPreservesVectorReplacementAndSearchSemantics() throws Exception {
        MapperXml mapper = readMapperXml();

        assertThat(mapper.namespace())
                .isEqualTo("xyz.v185v.collegeassistant.document.persistence.EmbeddingChunkMapper");
        assertThat(mapper.statements()).containsKeys(
                "findForReference",
                "setStrictHnswOrder",
                "search",
                "lockReplacement",
                "insertBatchReturning");

        assertCompleteReferenceIdentity(mapper.statement("lockReplacement"));
        assertThat(mapper.statement("lockReplacement"))
                .contains("pg_advisory_xact_lock")
                .contains("hashtextextended");

        assertCompleteReferenceIdentity(mapper.statement("findForReference"));

        assertThat(mapper.statement("setStrictHnswOrder"))
                .contains("SET LOCAL hnsw.iterative_scan = strict_order");

        String search = mapper.statement("search");
        assertThat(search)
                .contains("ec.embedding <=> CAST(#{queryEmbedding} AS vector)")
                .containsPattern("ec\\.embedding_config_id\\s*=\\s*#\\{embeddingConfigId}")
                .containsPattern("ec\\.source_type\\s*=\\s*#\\{sourceType")
                .containsPattern("ec\\.degree\\s*=\\s*#\\{degree")
                .containsPattern("ec\\.college\\s*=\\s*#\\{college")
                .containsPattern("ec\\.campus\\s*=\\s*#\\{campus")
                .containsPattern("ec\\.grade_range\\s*=\\s*#\\{gradeRange")
                .contains("official_document.reference_type AS document_reference_type")
                .contains("official_document.external_source_url AS external_source_url")
                .containsPattern("official_document\\.id\\s+IS\\s+NOT\\s+NULL")
                .containsPattern("approved_ugc\\.status\\s*=\\s*'APPROVED'")
                .containsPattern("approved_ugc\\.id\\s+IS\\s+NOT\\s+NULL")
                .contains("jsonb_build_object")
                .contains("'likes', approved_ugc.likes")
                .contains("'reports', approved_ugc.reports")
                .containsPattern("ORDER\\s+BY\\s+distance\\s+ASC\\s*,\\s*id\\s+ASC")
                .containsPattern("LIMIT\\s+#\\{topK}");

        assertThat(mapper.statement("insertBatchReturning"))
                .contains("CAST(#{chunk.embeddingText} AS vector)")
                .contains("CAST(#{chunk.metaJson} AS jsonb)")
                .contains("RETURNING");
        assertThat(mapper.xml())
                .contains("<foreach collection=\"chunks\" item=\"chunk\" separator=\",\">");
    }

    private void assertCompleteReferenceIdentity(String sql) {
        assertThat(sql)
                .contains("#{embeddingConfigId}")
                .contains("#{docRefType}")
                .contains("#{docRefId}");
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

        byte[] xml;
        try (InputStream resource = Objects.requireNonNull(
                getClass().getResourceAsStream(MAPPER_RESOURCE),
                "Missing mapper resource " + MAPPER_RESOURCE)) {
            xml = resource.readAllBytes();
        }
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml));
        Element mapper = document.getDocumentElement();
        Map<String, String> statements = new LinkedHashMap<>();
        NodeList children = mapper.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element statement && statement.hasAttribute("id")) {
                statements.put(
                        statement.getAttribute("id"),
                        statement.getTextContent().replaceAll("\\s+", " ").trim());
            }
        }
        return new MapperXml(
                mapper.getAttribute("namespace"),
                statements,
                new String(xml, java.nio.charset.StandardCharsets.UTF_8));
    }

    private record MapperXml(String namespace, Map<String, String> statements, String xml) {
        private String statement(String id) {
            return Objects.requireNonNull(statements.get(id), "Missing statement " + id);
        }
    }
}
