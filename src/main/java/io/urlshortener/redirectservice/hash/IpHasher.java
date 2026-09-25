package io.urlshortener.redirectservice.hash;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes client IP addresses one-way, so redirect-service never persists or publishes a raw IP.
 */
@Slf4j
@UtilityClass
public class IpHasher {

	/**
	 * Hashes an IP address with SHA-256.
	 *
	 * @param ipAddress the IP address to hash.
	 * @return the lowercase hex-encoded SHA-256 hash of {@code ipAddress}.
	 */
	public String hash(final String ipAddress) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hashBytes = digest.digest(ipAddress.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a mandatory algorithm on every standard Java platform -- this is not expected to ever
			// actually throw.
			log.atError()
					.addKeyValue("algorithm", "SHA-256")
					.setCause(e)
					.log("Required hashing algorithm unavailable");
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}

}
