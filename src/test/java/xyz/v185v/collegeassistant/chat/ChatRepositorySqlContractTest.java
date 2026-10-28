package xyz.v185v.collegeassistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class ChatRepositorySqlContractTest {

    private static final String MAPPER_RESOURCE = "/mapper/chat/ChatMapper.xml";

    @Test
    void mapperXmlContainsRequiredStatementsAndSafetyPredicates() throws Exception {
        MapperXml mapper = readMapperXml();

        assertThat(mapper.namespace())
                .isEqualTo("xyz.v185v.collegeassistant.chat.persistence.ChatMapper");
        assertThat(mapper.statements()).containsKeys(
                "createSession",
                "appendOwnedMessage",
                "reserveAssistantPlaceholder",
                "finalizeAssistantPlaceholder",
                "discardAssistantPlaceholder",
                "deleteStaleAssistantPlaceholders",
                "findOwnedSessions",
                "findOwnedMemory",
                "findOwnedDetailMessages");

        assertOwnedAssistantPlaceholderWrite(
                mapper.statement("finalizeAssistantPlaceholder"));
        assertOwnedAssistantPlaceholderWrite(
                mapper.statement("discardAssistantPlaceholder"));

        assertThat(mapper.statement("deleteStaleAssistantPlaceholders"))
                .containsPattern("m\\.role\\s*=\\s*'ASSISTANT'")
                .containsPattern("m\\.content\\s*=\\s*''")
                .containsPattern("m\\.created_at\\s*<\\s*#\\{cutoff}")
                .containsPattern("LIMIT\\s+#\\{batchSize}")
                .containsPattern("FOR\\s+UPDATE\\s+SKIP\\s+LOCKED");

        assertThat(mapper.statement("findOwnedMemory"))
                .containsPattern("SELECT\\s+recent\\.role\\s*,\\s*recent\\.content")
                .containsPattern("ORDER\\s+BY\\s+m\\.created_at\\s+DESC\\s*,\\s*m\\.id\\s+DESC")
                .containsPattern("LIMIT\\s+#\\{limit}")
                .containsPattern("ORDER\\s+BY\\s+recent\\.created_at\\s+ASC\\s*,\\s*recent\\.id\\s+ASC")
                .doesNotContain("sources", "recent.*", "m.*");

        assertThat(mapper.statement("findOwnedDetailMessages"))
                .containsPattern("s\\.user_id\\s*=\\s*#\\{userId}")
                .containsPattern("ORDER\\s+BY\\s+m\\.created_at\\s+DESC\\s*,\\s*m\\.id\\s+DESC")
                .containsPattern("LIMIT\\s+#\\{fetchLimit}");
    }

    private void assertOwnedAssistantPlaceholderWrite(String sql) {
        assertThat(sql)
                .containsPattern("m\\.id\\s*=\\s*#\\{messageId}")
                .containsPattern("m\\.session_id\\s*=\\s*#\\{sessionId}")
                .containsPattern("s\\.user_id\\s*=\\s*#\\{userId}")
                .containsPattern("m\\.role\\s*=\\s*'ASSISTANT'")
                .containsPattern("m\\.content\\s*=\\s*''");
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
