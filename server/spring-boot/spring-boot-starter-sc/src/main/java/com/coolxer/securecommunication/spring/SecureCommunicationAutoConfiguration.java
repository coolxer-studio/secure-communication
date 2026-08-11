package com.coolxer.securecommunication.spring;

import com.coolxer.securecommunication.core.AesGcmAlgorithmProvider;
import com.coolxer.securecommunication.core.DefaultSecurityPolicy;
import com.coolxer.securecommunication.core.EnvelopeCodec;
import com.coolxer.securecommunication.core.InMemoryReplayProtector;
import com.coolxer.securecommunication.core.JacksonEnvelopeCodec;
import com.coolxer.securecommunication.core.InMemorySessionRepository;
import com.coolxer.securecommunication.core.InMemoryInstallationRegistry;
import com.coolxer.securecommunication.core.SecureMessageService;
import com.coolxer.securecommunication.servlet.V1SecureCommunicationFilter;
import com.coolxer.securecommunication.spi.AlgorithmProvider;
import com.coolxer.securecommunication.spi.KeyProvider;
import com.coolxer.securecommunication.spi.ReplayProtector;
import com.coolxer.securecommunication.spi.SecurityPolicy;
import com.coolxer.securecommunication.spi.LogicalRouteAuthorizer;
import com.coolxer.securecommunication.spi.SessionRepository;
import com.coolxer.securecommunication.spi.InstallationRegistry;
import com.coolxer.securecommunication.spi.EnrollmentTokenService;
import com.coolxer.securecommunication.spi.HandshakeAuthorizer;
import com.coolxer.securecommunication.spi.ServerIdentityProvider;
import com.coolxer.securecommunication.handshake.HandshakeService;
import com.coolxer.securecommunication.handshake.HandshakeController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(SecureCommunicationProperties.class)
@ConditionalOnClass(FilterRegistrationBean.class)
@ConditionalOnProperty(prefix = "spring.sc", name = "enabled", havingValue = "true")
public class SecureCommunicationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EnvelopeCodec secureEnvelopeCodec() {
        return new JacksonEnvelopeCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionRepository secureSessionRepository() {
        return new InMemorySessionRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public InstallationRegistry secureInstallationRegistry() {
        return new InMemoryInstallationRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public EnrollmentTokenService secureEnrollmentTokenService() {
        return new EnrollmentTokenService() {
            @Override
            public String issue(String appId, String deviceType, java.time.Duration ttl)
                    throws com.coolxer.securecommunication.protocol.SecureProtocolException {
                throw new com.coolxer.securecommunication.protocol.SecureProtocolException(
                        com.coolxer.securecommunication.protocol.SecureErrorCode.ENROLLMENT_REQUIRED);
            }

            @Override
            public void consume(String token, String appId, String deviceId, String deviceType)
                    throws com.coolxer.securecommunication.protocol.SecureProtocolException {
                throw new com.coolxer.securecommunication.protocol.SecureProtocolException(
                        com.coolxer.securecommunication.protocol.SecureErrorCode.ENROLLMENT_REQUIRED);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public HandshakeAuthorizer secureHandshakeAuthorizer() {
        return context -> {
            throw new com.coolxer.securecommunication.protocol.SecureProtocolException(
                    com.coolxer.securecommunication.protocol.SecureErrorCode.HANDSHAKE_FAILED);
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ReplayProtector secureReplayProtector() {
        return new InMemoryReplayProtector();
    }

    @Bean
    @ConditionalOnMissingBean(name = "aesGcmAlgorithmProvider")
    public AlgorithmProvider aesGcmAlgorithmProvider() {
        return new AesGcmAlgorithmProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityPolicy secureSecurityPolicy(SecureCommunicationProperties properties) {
        SecureCommunicationProperties.V1 v1 = properties.getV1();
        return new DefaultSecurityPolicy(
                v1.getAllowedSuites(),
                v1.isRequireTls(),
                v1.getClockSkew(),
                v1.getReplayTtl(),
                v1.getMaxEnvelopeBytes(),
                v1.getMaxPlaintextBytes(),
                v1.getMaxBodyBytes(),
                java.time.Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecureMessageService secureMessageService(
            EnvelopeCodec envelopeCodec,
            KeyProvider keyProvider,
            ReplayProtector replayStore,
            SecurityPolicy securityPolicy,
            List<AlgorithmProvider> algorithmProviders) {
        return new SecureMessageService(
                envelopeCodec, keyProvider, replayStore, securityPolicy, algorithmProviders);
    }

    @Bean
    @ConditionalOnMissingBean
    public LogicalRouteAuthorizer secureLogicalRouteAuthorizer() {
        return (method, path) -> false;
    }

    @Bean
    @ConditionalOnBean(ServerIdentityProvider.class)
    @ConditionalOnMissingBean
    public HandshakeService secureHandshakeService(
            ServerIdentityProvider identity,
            SessionRepository sessions,
            InstallationRegistry installations,
            EnrollmentTokenService enrollmentTokens,
            HandshakeAuthorizer authorizer,
            SecureCommunicationProperties properties,
            SecurityPolicy policy) {
        return new HandshakeService(
                identity, sessions, installations, enrollmentTokens, authorizer,
                properties.getV1().getSessionTtl(), properties.getV1().getClockSkew(),
                policy.clock());
    }

    @Bean
    @ConditionalOnBean(HandshakeService.class)
    @ConditionalOnMissingBean
    public HandshakeController secureHandshakeController(
            HandshakeService service, SecurityPolicy policy) {
        return new HandshakeController(service, policy);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "spring.sc.v1", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public FilterRegistrationBean<V1SecureCommunicationFilter> v1SecureCommunicationFilter(
            SecureCommunicationProperties properties,
            SecureMessageService messageService,
            SecurityPolicy securityPolicy,
            LogicalRouteAuthorizer logicalRouteAuthorizer) {
        V1SecureCommunicationFilter filter = new V1SecureCommunicationFilter(
                properties.getV1().getPrefix(), messageService, securityPolicy,
                logicalRouteAuthorizer);
        FilterRegistrationBean<V1SecureCommunicationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setName("v1SecureCommunicationFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(-200);
        return registration;
    }

}
