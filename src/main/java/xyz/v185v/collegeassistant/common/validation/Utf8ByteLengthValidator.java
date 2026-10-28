package xyz.v185v.collegeassistant.common.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf8ByteLengthValidator
        implements ConstraintValidator<Utf8ByteLength, String> {

    private int max;

    @Override
    public void initialize(Utf8ByteLength constraintAnnotation) {
        max = constraintAnnotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
