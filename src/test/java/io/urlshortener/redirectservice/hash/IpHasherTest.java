package io.urlshortener.redirectservice.hash;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IpHasherTest {

	@Test
	void hash_shouldReturn64CharacterHexString_whenGivenAnIpAddress() {
		// Arrange
		final String ipAddress = "203.0.113.42";

		// Act
		final String result = IpHasher.hash(ipAddress);

		// Assert
		assertThat(result).hasSize(64).matches("[0-9a-f]+");
	}

	@Test
	void hash_shouldReturnSameHash_whenGivenTheSameIpAddressTwice() {
		// Arrange
		final String ipAddress = "203.0.113.42";

		// Act
		final String first = IpHasher.hash(ipAddress);
		final String second = IpHasher.hash(ipAddress);

		// Assert
		assertThat(first).isEqualTo(second);
	}

	@Test
	void hash_shouldReturnDifferentHashes_whenGivenDifferentIpAddresses() {
		// Arrange
		final String first = "203.0.113.42";
		final String second = "198.51.100.7";

		// Act & Assert
		assertThat(IpHasher.hash(first)).isNotEqualTo(IpHasher.hash(second));
	}

}
