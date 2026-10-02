package ma.mystix.format.ubl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Validates UBL 2.1 invoices against the EN 16931 business rules, using the official CEN validation artefacts
 * (Schematron compiled to XSLT, bundled under {@code en16931/ubl-1.3.16}).
 * <p>
 * Thread-safe: the stylesheet is compiled once, each call uses its own transformer.
 */
public final class En16931Validator {

    public static final String ARTEFACTS_VERSION = "1.3.16";

    private static final String XSLT = "en16931/ubl-" + ARTEFACTS_VERSION + "/EN16931-UBL-validation.xslt";
    private static final String SVRL_NS = "http://purl.oclc.org/dsdl/svrl";
    private static final QName FAILED_ASSERT = new QName(SVRL_NS, "failed-assert");
    private static final QName TEXT = new QName(SVRL_NS, "text");

    /** Severity as declared by the rule ({@code flag} attribute in the Schematron). */
    public enum Severity {
        FATAL, WARNING;

        static Severity of(String flag) {
            return "warning".equals(flag) ? WARNING : FATAL;
        }
    }

    /**
     * One failed business rule.
     *
     * @param ruleId   rule identifier, e.g. BR-CO-15
     * @param severity fatal rules make the invoice non compliant
     * @param location XPath of the offending node
     * @param message  rule text in English, as published by CEN
     */
    public record Violation(String ruleId, Severity severity, String location, String message) {
    }

    private final Processor processor = new Processor(false);
    private final XsltExecutable executable;

    public En16931Validator() {
        try (InputStream xslt = Objects.requireNonNull(
                En16931Validator.class.getClassLoader().getResourceAsStream(XSLT), XSLT)) {
            this.executable = processor.newXsltCompiler().compile(new StreamSource(xslt, XSLT));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compile EN 16931 validation artefacts " + XSLT, e);
        }
    }

    /** Returns every failed rule (fatal and warning), in document order. */
    public List<Violation> validate(byte[] ublDocument) {
        try {
            XsltTransformer transformer = executable.load();
            transformer.setSource(secureSource(ublDocument));
            XdmDestination svrl = new XdmDestination();
            transformer.setDestination(svrl);
            transformer.transform();
            return collect(svrl.getXdmNode());
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Document cannot be evaluated against EN 16931 rules", e);
        }
    }

    /** True when no fatal rule failed. */
    public boolean isCompliant(byte[] ublDocument) {
        return validate(ublDocument).stream().noneMatch(v -> v.severity() == Severity.FATAL);
    }

    /** Partner documents are untrusted: no DOCTYPE, hence no entity expansion and no external resource. */
    private static SAXSource secureSource(byte[] document) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            return new SAXSource(reader, new InputSource(new ByteArrayInputStream(document)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("Cannot configure secure XML parser", e);
        }
    }

    private static List<Violation> collect(XdmNode node) {
        List<Violation> violations = new ArrayList<>();
        walk(node, violations);
        return List.copyOf(violations);
    }

    private static void walk(XdmNode node, List<Violation> violations) {
        for (XdmNode child : node.children()) {
            if (child.getNodeKind() != XdmNodeKind.ELEMENT) {
                continue;
            }
            if (FAILED_ASSERT.equals(child.getNodeName())) {
                violations.add(new Violation(
                        child.getAttributeValue(new QName("id")),
                        Severity.of(child.getAttributeValue(new QName("flag"))),
                        child.getAttributeValue(new QName("location")),
                        text(child)));
            } else {
                walk(child, violations);
            }
        }
    }

    private static String text(XdmNode failedAssert) {
        for (XdmNode child : failedAssert.children()) {
            if (child.getNodeKind() == XdmNodeKind.ELEMENT && TEXT.equals(child.getNodeName())) {
                return child.getStringValue().strip();
            }
        }
        return "";
    }
}
