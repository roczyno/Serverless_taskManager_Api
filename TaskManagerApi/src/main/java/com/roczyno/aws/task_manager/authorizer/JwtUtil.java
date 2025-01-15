package com.roczyno.aws.task_manager.authorizer;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.RSAKeyProvider;

public class JwtUtil {

	/**
	 * Validates a JWT token without requiring a specific user
	 */
	public DecodedJWT validateJwt(String jwt, String region, String userPoolId, String audience) {
		RSAKeyProvider keyProvider = new AwsCognitoRSAKeyProvider(region, userPoolId);

		Algorithm algorithm = Algorithm.RSA256(keyProvider);
		JWTVerifier jwtVerifier = JWT.require(algorithm)
				.withIssuer("https://cognito-idp." + region + ".amazonaws.com/" + userPoolId)
				.withClaim("token_use", "access")  // Changed from 'id' to 'access'
				// .withAudience(audience)  // Removed as access tokens use client_id
				.build();

		return jwtVerifier.verify(jwt);
	}

	/**
	 * Validates a JWT token for a specific user
	 */
	public DecodedJWT validateJwtForUser(String jwt, String region, String userPoolId, String principalId, String audience) {
		RSAKeyProvider keyProvider = new AwsCognitoRSAKeyProvider(region, userPoolId);

		Algorithm algorithm = Algorithm.RSA256(keyProvider);
		JWTVerifier jwtVerifier = JWT.require(algorithm)
				.withSubject(principalId)
				.withAudience(audience)
				.withIssuer("https://cognito-idp." + region + ".amazonaws.com/" + userPoolId)
				.withClaim("token_use", "id")
				.build();

		return jwtVerifier.verify(jwt);
	}
}
