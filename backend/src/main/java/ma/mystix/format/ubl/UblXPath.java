package ma.mystix.format.ubl;

import java.io.ByteArrayInputStream;
import java.util.Iterator;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

/** Reads values out of a UBL 2.1 invoice with the inv / cac / cbc prefixes of {@link UblMappingSpec}. */
public final class UblXPath {

    private static final Map<String, String> NS = Map.of(
            "inv", "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2",
            "cac", "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2",
            "cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2");

    private final Document document;
    private final XPath xpath;

    public UblXPath(byte[] ubl) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            this.document = builder.parse(new ByteArrayInputStream(ubl));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a readable UBL document", e);
        }
        this.xpath = XPathFactory.newInstance().newXPath();
        this.xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return NS.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
            }

            @Override
            public String getPrefix(String uri) {
                return null;
            }

            @Override
            public Iterator<String> getPrefixes(String uri) {
                return null;
            }
        });
    }

    /** Text of the node at {@code path}, or {@code null} when the node does not exist. */
    public String text(String path) {
        try {
            Node node = (Node) xpath.evaluate(path, document, XPathConstants.NODE);
            return node == null ? null : node.getTextContent();
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException("Invalid XPath " + path, e);
        }
    }
}
