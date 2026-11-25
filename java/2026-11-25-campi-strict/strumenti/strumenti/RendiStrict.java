package strumenti;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFileVersion;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class RendiStrict {

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private RendiStrict() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            OUT.println("Uso: java -cp <classi> strumenti.RendiStrict <file .class> <campo>...");
            System.exit(1);
        }
        var file = Path.of(args[0]);
        var campi = List.of(Arrays.copyOfRange(args, 1, args.length));
        var classFile = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        var classe = classFile.parse(Files.readAllBytes(file));
        var presenti = classe.fields().stream()
                .map(campo -> campo.fieldName().stringValue())
                .collect(Collectors.toSet());
        if (!presenti.containsAll(campi)) {
            OUT.println(file + " non ha tutti i campi " + campi + ": ci sono " + presenti);
            System.exit(1);
        }
        var bytes = classFile.transformClass(classe, (builder, elemento) -> {
            switch (elemento) {
                case ClassFileVersion _ -> builder.withVersion(
                        ClassFile.latestMajorVersion(), ClassFile.PREVIEW_MINOR_VERSION);
                case FieldModel campo when campi.contains(campo.fieldName().stringValue()) ->
                        builder.transformField(campo, conStrictInit(campo));
                default -> builder.with(elemento);
            }
        });
        Files.write(file, bytes);
        OUT.println(file + ": " + String.join(", ", campi) + " strict");
    }

    private static FieldTransform conStrictInit(FieldModel campo) {
        var flag = campo.flags().flagsMask() | ClassFile.ACC_STRICT_INIT;
        return (builder, elemento) -> {
            if (elemento instanceof AccessFlags) {
                builder.withFlags(flag);
            } else {
                builder.with(elemento);
            }
        };
    }
}
