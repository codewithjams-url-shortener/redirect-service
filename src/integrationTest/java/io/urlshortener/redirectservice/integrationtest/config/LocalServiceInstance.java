package io.urlshortener.redirectservice.integrationtest.config;

import java.util.concurrent.TimeUnit;

/**
 * A service instance launched as a genuine separate OS process by {@link LocalEnvironmentConfig}, together with the
 * port it is listening on. Used for both {@code redirect-service} (the system under test) and {@code url-service}
 * (needed only to create/delete the Links rows {@code redirect-service} has no write endpoint for).
 */
public record LocalServiceInstance(Process process, int port) {

	/**
	 * Stops the process, escalating to a forceful kill if it does not exit promptly.
	 */
	public void shutdown() {
		process.destroy();
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}

}
