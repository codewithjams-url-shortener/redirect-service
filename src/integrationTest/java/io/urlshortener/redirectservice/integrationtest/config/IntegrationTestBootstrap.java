package io.urlshortener.redirectservice.integrationtest.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Purpose-built, minimal Spring Boot context for the integration tests in this source set. Deliberately not
 * {@code RedirectServiceApplication} itself: these tests treat both {@code redirect-service} and {@code url-service}
 * as HTTP black boxes (separately launched processes for the {@code local} profile, already-deployed instances for
 * {@code deployed}), so this context exists only to host the {@code @Profile}-gated
 * {@link org.springframework.web.client.RestClient RestClient} beans, never to run either application under test
 * in-process.
 */
@SpringBootApplication
public class IntegrationTestBootstrap {
}
