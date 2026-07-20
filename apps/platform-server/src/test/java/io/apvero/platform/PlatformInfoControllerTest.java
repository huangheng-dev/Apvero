package io.apvero.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class PlatformInfoControllerTest {

    @Test
    void exposesCanonicalProductNameAndLocales() {
        var properties = new Properties();
        properties.setProperty("version", "0.1.0-test");
        var info = new PlatformInfoController(new BuildProperties(properties)).platformInfo();

        assertThat(info.name()).isEqualTo("Apvero");
        assertThat(info.version()).isEqualTo("0.1.0-test");
        assertThat(info.sourceLocale()).isEqualTo("en");
        assertThat(info.supportedLocales()).containsExactly("en", "zh-CN");
    }
}
