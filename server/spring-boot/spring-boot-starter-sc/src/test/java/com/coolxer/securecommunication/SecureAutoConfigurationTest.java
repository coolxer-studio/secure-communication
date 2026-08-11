package com.coolxer.securecommunication;

import com.coolxer.securecommunication.spring.SecureCommunicationAutoConfiguration;
import com.coolxer.securecommunication.handshake.HandshakeController;
import com.coolxer.securecommunication.servlet.V1SecureCommunicationFilter;
import com.coolxer.securecommunication.spi.KeyProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

class SecureAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            SecureCommunicationAutoConfiguration.class));

    @Test
    void isOptInAndRegistersV1FailClosed() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));

        contextRunner.withPropertyValues("spring.sc.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(KeyProvider.class);
                    FilterRegistrationBean<?> registration = context.getBean(
                            "v1SecureCommunicationFilter", FilterRegistrationBean.class);
                    assertThat(registration.getFilter())
                            .isInstanceOf(V1SecureCommunicationFilter.class);
                    assertThat(context).doesNotHaveBean("legacySecureCommunicationFilter");
                });
    }

    @Test
    void handshakeBeanIsAnMvcHandler() {
        assertThat(AnnotatedElementUtils.hasAnnotation(
                HandshakeController.class, RestController.class)).isTrue();
    }
}
