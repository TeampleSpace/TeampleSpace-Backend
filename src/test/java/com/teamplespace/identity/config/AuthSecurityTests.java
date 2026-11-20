package com.teamplespace.identity.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.teamplespace.identity.infra.ProviderOAuthGateway;
import com.teamplespace.identity.service.AuthSecrets;

class AuthSecurityTests {
    @Test void missingConfigurationClosesAuthenticationAndConsent() {
        var settings=new AuthSettings(new MockEnvironment());
        assertThat(settings.configured()).isFalse();
        assertThat(settings.mailEnabled()).isFalse();
        assertThat(settings.oauthEnabled("google")).isFalse();
        assertThat(settings.legalDocuments()).isEmpty();
    }
    @Test void providerReauthenticationUsesDedicatedParameters() {
        var settings=new AuthSettings(new MockEnvironment().withProperty("teamplespace.auth.backend-origin","https://api.example.test"));
        var provider=new ProviderOAuthGateway(settings,new AuthSecrets(settings));
        assertThat(provider.authorizationUrl("kakao","state","nonce","verifier","reauth")).contains("prompt=login");
        assertThat(provider.authorizationUrl("naver","state","nonce","verifier","reauth")).contains("auth_type=reauthenticate");
        assertThat(provider.authorizationUrl("kakao","state","nonce","verifier","login")).doesNotContain("prompt=");
    }
}
