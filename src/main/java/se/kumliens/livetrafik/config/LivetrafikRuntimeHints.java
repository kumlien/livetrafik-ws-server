package se.kumliens.livetrafik.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import se.kumliens.livetrafik.model.VehicleBroadcastPayload;

public class LivetrafikRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern("META-INF/build-info.properties");
        hints.reflection().registerType(VehicleBroadcastPayload.class,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
