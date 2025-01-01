package com.roczyno.aws.task_manager;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import java.util.Base64;

public class Utils {

	public static String decrypt(String encryptedText) {
		try {
			// Create KMS client
			KmsClient kmsClient = KmsClient.builder().build();

			// Use URL-safe Base64 decoder
			byte[] decodedBytes = Base64.getUrlDecoder().decode(encryptedText);

			// Create decrypt request
			DecryptRequest request = DecryptRequest.builder()
					.ciphertextBlob(SdkBytes.fromByteArray(decodedBytes))
					.build();

			// Decrypt the text
			DecryptResponse response = kmsClient.decrypt(request);

			// Convert the decrypted bytes to string
			return new String(response.plaintext().asByteArray());

		} catch (Exception e) {
			throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
		}
	}
}
