package veclite.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoreNameValidatorTest {
    @Test
    void acceptsSafeNames() {
        assertDoesNotThrow(() -> StoreNameValidator.validate("store-01"));
    }

    @Test
    void rejectsUnsafeAndReservedNames() {
        assertThrows(IllegalArgumentException.class, () -> StoreNameValidator.validate("1store"));
        assertThrows(IllegalArgumentException.class, () -> StoreNameValidator.validate("veclite_store_meta"));
    }
}
