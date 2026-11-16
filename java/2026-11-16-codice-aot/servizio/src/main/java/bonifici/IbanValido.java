package bonifici;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ControlloIban.class)
public @interface IbanValido {

    String message() default "IBAN non valido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
