package com.roczyno.aws.task_manager;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class Utils {
	private static final KmsClient kmsClient = KmsClient.builder().build();

//	public static String decrypt(String encryptedText) {
//		try {
//			// Create KMS client
//			KmsClient kmsClient = KmsClient.builder().build();
//
//			// Use URL-safe Base64 decoder
//			byte[] decodedBytes = Base64.getUrlDecoder().decode(encryptedText);
//
//			// Create decrypt request
//			DecryptRequest request = DecryptRequest.builder()
//					.ciphertextBlob(SdkBytes.fromByteArray(decodedBytes))
//					.build();
//
//			// Decrypt the text
//			DecryptResponse response = kmsClient.decrypt(request);
//
//			// Convert the decrypted bytes to string
//			return new String(response.plaintext().asByteArray());
//
//		} catch (Exception e) {
//			throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
//		}
//	}

	/**
	 * Decrypts an encrypted key stored in an environment variable
	 *
	 * @param envVarName name of environment variable containing the encrypted key
	 * @return decrypted key as a string
	 * @throws KeyDecryptionException if decryption fails
	 */
	public static String decryptKey(String envVarName) throws KeyDecryptionException {
		if (envVarName == null || envVarName.trim().isEmpty()) {
			throw new KeyDecryptionException("Environment variable name cannot be null or empty");
		}

		String encryptedValue = System.getenv(envVarName);
		if (encryptedValue == null || encryptedValue.trim().isEmpty()) {
			throw new KeyDecryptionException("No value found for environment variable: " + envVarName);
		}

		try {
			// Decode the Base64 encoded encrypted key
			byte[] encryptedKey = Base64.getDecoder().decode(encryptedValue.trim());

			// Create and execute decrypt request
			DecryptRequest request = DecryptRequest.builder()
					.ciphertextBlob(SdkBytes.fromByteArray(encryptedKey))
					.build();

			DecryptResponse response = kmsClient.decrypt(request);

			// Convert the decrypted bytes to string using UTF-8
			return new String(response.plaintext().asByteArray(), StandardCharsets.UTF_8);

		} catch (IllegalArgumentException e) {
			throw new KeyDecryptionException("Invalid Base64 encoding in environment variable: " + envVarName, e);
		} catch (KmsException e) {
			throw new KeyDecryptionException("KMS service error while decrypting key: " + e.getMessage(), e);
		} catch (Exception e) {
			throw new KeyDecryptionException("Unexpected error while decrypting key: " + e.getMessage(), e);
		}
	}

	/**
	 * Custom exception for key decryption errors
	 */
	public static class KeyDecryptionException extends Exception {
		public KeyDecryptionException(String message) {
			super(message);
		}

		public KeyDecryptionException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
