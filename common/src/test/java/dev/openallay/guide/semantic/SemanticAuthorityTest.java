package dev.openallay.guide.semantic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SemanticAuthorityTest {
    @Test
    void documentCannotBeReboundToAnotherRequestIndex() {
        SemanticReferenceIndex source = SemanticReferenceValidatorTest.index();
        SemanticDocument document = new SemanticMessageParser().parse(
                "[[tw:item|minecraft:iron_block|Iron block]]", source);
        SemanticDocumentValidator validator = new SemanticDocumentValidator();

        assertDoesNotThrow(() -> validator.validate(
                SemanticReferenceValidatorTest.request(), document, source));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                UUID.randomUUID(), document, source));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                SemanticReferenceValidatorTest.request(),
                document,
                SemanticReferenceIndex.empty(SemanticReferenceValidatorTest.request())));
    }
}
