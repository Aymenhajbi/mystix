package ma.mystix.invoice;

import ma.mystix.canonical.InvoiceCalculator;
import ma.mystix.format.ubl.En16931Validator;
import ma.mystix.format.ubl.UblInvoiceGenerator;
import ma.mystix.format.ubl.UblSchemaValidator;
import ma.mystix.format.ubl.UblSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure-Java canonical and UBL components. Validators compile their schemas once, at startup. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvoiceProperties.class)
class InvoiceConfig {

    @Bean
    InvoiceCalculator invoiceCalculator(InvoiceProperties properties) {
        return new InvoiceCalculator(properties.roundingMode());
    }

    @Bean
    UblInvoiceGenerator ublInvoiceGenerator(InvoiceProperties properties) {
        InvoiceProperties.Ubl ubl = properties.ubl();
        return new UblInvoiceGenerator(
                new UblSettings(ubl.customizationId(), ubl.iceSchemeId(), ubl.taxIdentifierSchemeId()));
    }

    @Bean
    UblSchemaValidator ublSchemaValidator() {
        return new UblSchemaValidator();
    }

    @Bean
    En16931Validator en16931Validator() {
        return new En16931Validator();
    }
}
