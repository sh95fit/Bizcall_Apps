package com.bizcall.app.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class RegisterRequest(
    val token: String,
    val device_id: String
)

data class RegisterResponse(
    val phone_id: String,
    val name: String,
    val is_active: Boolean
)

data class CredentialsRequest(
    val phone_id: String,
    val token: String
)

data class CredentialsResponse(
    val access_key_id: String,
    val secret_access_key: String,
    val session_token: String,
    val expiration: String,
    val bucket: String,
    val region: String
)

interface PhoneApiService {

    @POST("phones/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("phones/credentials")
    suspend fun getCredentials(@Body request: CredentialsRequest): Response<CredentialsResponse>
}
