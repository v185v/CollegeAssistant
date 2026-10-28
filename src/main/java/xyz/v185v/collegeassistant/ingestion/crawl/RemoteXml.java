package xyz.v185v.collegeassistant.ingestion.crawl;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 面向 RSS、Atom 和 sitemap 载荷的 XXE 安全 XML 提取。 */
final class RemoteXml {

    private RemoteXml() {
    }

    static ParsedXml parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Element root = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            String rootName = localName(root);
            List<String> locations = new ArrayList<>();
            NodeList nodes = root.getElementsByTagNameNS("*", "loc");
            for (int index = 0; index < nodes.getLength(); index++) {
                String value = nodes.item(index).getTextContent();
                if (value != null && !value.isBlank()) {
                    locations.add(value.strip());
                }
            }
            return new ParsedXml(rootName, List.copyOf(locations), rssLinks(root));
        } catch (Exception exception) {
            throw RemoteConnectorSupport.failure(exception);
        }
    }

    private static List<String> rssLinks(Element root) {
        List<String> links = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", "link");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }
            String href = element.getAttribute("href");
            String value = href == null || href.isBlank() ? element.getTextContent() : href;
            if (value != null && !value.isBlank()) {
                links.add(value.strip());
            }
        }
        return List.copyOf(links);
    }

    private static String localName(Element element) {
        String local = element.getLocalName();
        return local == null ? element.getTagName() : local;
    }

    record ParsedXml(String rootName, List<String> locations, List<String> rssLinks) {
    }
}
