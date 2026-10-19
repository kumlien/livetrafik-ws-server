package se.kumliens.livetrafik.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SupabasePropertiesTest {

    private static SupabaseProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
            .bindOrCreate("supabase", SupabaseProperties.class);
    }

    @Test
    void csvValuesFromEnvironmentStillBindAsTrimmedLists() {
        SupabaseProperties properties = bind(Map.of(
            "supabase.regions", "ul, sl ,vt",
            "supabase.vehicle-types", "bus,train",
            "supabase.channel", "ul/vehicles/bus, sl/vehicles/bus"));

        assertThat(properties.getRegions()).containsExactly("ul", "sl", "vt");
        assertThat(properties.getVehicleTypes()).containsExactly("bus", "train");
        assertThat(properties.getChannel()).containsExactly("ul/vehicles/bus", "sl/vehicles/bus");
    }

    @Test
    void unsetValuesFallBackToTheTwoProductionRegionsAndTypes() {
        SupabaseProperties properties = bind(Map.of("supabase.url", "wss://example"));

        assertThat(properties.getRegions()).containsExactly("ul", "sl");
        assertThat(properties.getVehicleTypes()).containsExactly("bus", "train");
        assertThat(properties.getChannel()).isEmpty();
    }

    @Test
    void blankChannelOverrideKeepsTheComputedChannelList() {
        SupabaseProperties properties = bind(Map.of("supabase.channel", ""));

        assertThat(properties.getChannel()).isEmpty();
    }
}
