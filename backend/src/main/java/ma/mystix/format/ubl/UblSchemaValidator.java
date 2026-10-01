package ma.mystix.format.ubl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Validates documents against the official OASIS UBL 2.1 schemas bundled under {@code xsd/ubl-2.1}.
 * Schema validation only: EN 16931 business rules (Schematron) are a separate step.
 */
public final class UblSchemaValidator {

    private static final String INVOICE_XSD = "xsd/ubl-2.1/maindoc/UBL-Invoice-2.1.xsd";

    private final Schema invoiceSchema;

    public UblSchemaValidator() {
        this.invoiceSchema = load(INVOICE_XSD);
    }

    /** Returns the schema violations, empty when the document is valid. */
    public List<String> validateInvoice(byte[] document) {
        List<String> errors = new ArrayList<>();
        Validator validator = invoiceSchema.newValidator();
        try {
            // Instance documents may not pull external DTDs or schemas.
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setErrorHandler(new CollectingHandler(errors));
            validator.validate(new StreamSource(new ByteArrayInputStream(document)));
        } catch (SAXException e) {
            if (errors.isEmpty()) {
                errors.add(e.getMessage());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read document", e);
        }
        return List.copyOf(errors);
    }

    private static Schema load(String resource) {
        URL url = Objects.requireNonNull(UblSchemaValidator.class.getClassLoader().getResource(resource), resource);
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            // Bundled schemas include each other through relative paths: allow local files and jars only.
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar:file");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            return factory.newSchema(url);
        } catch (SAXException e) {
            throw new IllegalStateException("Cannot load UBL schema " + resource, e);
        }
    }

    private record CollectingHandler(List<String> errors) implements ErrorHandler {

        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) {
            errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
        }

        @Override
        public void fatalError(SAXParseException e) {
            errors.add("line " + e.getLineNumber() + ": " + e.getMessage());
        }
    }
}
