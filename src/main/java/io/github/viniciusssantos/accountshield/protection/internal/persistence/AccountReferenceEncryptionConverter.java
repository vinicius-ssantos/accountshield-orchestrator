package io.github.viniciusssantos.accountshield.protection.internal.persistence;

import io.github.viniciusssantos.accountshield.crypto.FieldEncryptionService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Encrypts {@code account_reference} at rest via the crypto module's envelope encryption
 * service. Spring Boot's Hibernate integration resolves this converter through the application
 * context (because it is a {@code @Component}), so it can depend on a Spring-managed bean rather
 * than only on JPA's no-argument construction contract.
 */
@Component
@Converter
public class AccountReferenceEncryptionConverter implements AttributeConverter<String, String> {

    private final FieldEncryptionService fieldEncryptionService;

    public AccountReferenceEncryptionConverter(FieldEncryptionService fieldEncryptionService) {
        this.fieldEncryptionService = fieldEncryptionService;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : fieldEncryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : fieldEncryptionService.decrypt(dbData);
    }
}
