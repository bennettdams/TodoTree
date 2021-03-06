package com.example.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.example.repository.UserRepository
import com.mongodb.client.MongoClient
import io.ktor.application.*
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.nio.charset.StandardCharsets
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.example.model.DecodedTokens
import com.example.model.EncodedTokens
import com.example.model.LoggedInUser
import com.example.model.User
import io.ktor.response.*
import java.util.*

class AuthService: KoinComponent {
    private val client: MongoClient by inject()
    private val repo: UserRepository = UserRepository(client)
    private val secret: String = "peepeepoopoo"
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)
    private val verifier: JWTVerifier = JWT.require(algorithm).build()

    /**
     * @param call ktor application call with access to getting and setting headers
     * @param email the email of the user signing in
     * @param password the password of the user signing in
     *
     * @return EncodedTokens object with no null values if sign in successful
     * EncodedTokens object with two null values if unsuccessful
     */
    fun signIn(call: ApplicationCall, email: String, password: String): EncodedTokens {

            val user = repo.getUserByEmail(email) ?: error("No such user by that email")

            // hash incoming password and compare it to saved
            if (!BCrypt.verifyer()
                    .verify(
                        password.toByteArray(StandardCharsets.UTF_8),
                        user.hashedPass
                    ).verified
            ) {
                error("Password incorrect")
            }

            val permissionLevel = user.permissionLevel
            val count = user.count

            val accessToken = signAccessToken(user.id, permissionLevel)
            val refreshToken = signRefreshToken(user.id, permissionLevel, count)

            setAccessTokens(call, accessToken, refreshToken)

            return EncodedTokens(
                AccessToken = accessToken,
                RefreshToken = refreshToken
            )
    }

    /**
     * @param call ktor application call with access to getting and setting headers
     * @param email the email of the user signing up
     * @param password the password of the user signing up
     * @param permissionLevel the permission level of the user
     *
     * @return EncodedTokens object with no null values if sign in successful
     * EncodedTokens object with two null values if unsuccessful
     */
    fun signUp(
        call: ApplicationCall,
        email: String,
        password: String,
        permissionLevel: String = "user"
    ): EncodedTokens {

            val hashedPassword = BCrypt.withDefaults().hash(10, password.toByteArray(StandardCharsets.UTF_8))
            val id = UUID.randomUUID().toString()
            val emailUser = repo.getUserByEmail(email)
            if (emailUser != null) { error("Email already in use") }
            val newUser = repo.add(
                User(
                    id = id,
                    email = email,
                    hashedPass = hashedPassword,
                    count = 0,
                    permissionLevel = permissionLevel
                )
            )

            val accessToken = signAccessToken(newUser.id, permissionLevel = newUser.permissionLevel)
            val refreshToken = signRefreshToken(newUser.id,
                permissionLevel = newUser.permissionLevel,
                count = newUser.count)

            setAccessTokens(call, accessToken, refreshToken)
            return EncodedTokens(
                    AccessToken = accessToken,
                    RefreshToken = refreshToken
            )
    }

    /**
     * Method verifies that tokens are correct and returns access tokens decoded.
     * If access token and refresh token are valid decoded access token is returned.
     * if access token is invalid and refresh token is valid a new access token is granted.
     * and both tokens are reset
     * If the refresh token and access token are invalid null is returns
     * cookies are set to the cookies if they are verified, null otherwise
     *
     * @param call ktor object that has ability to set headers as needed
     *
     * @return a decoded AccessToken if verified. null otherwise
     */
    fun verifyToken(call: ApplicationCall): LoggedInUser? {
        val encodedTokens = getAccessTokens(call)
        val (accessToken , refreshToken) = try {

            val accessToken = verifier.verify(JWT.decode(encodedTokens.AccessToken))
            val refreshToken = verifier.verify(JWT.decode(encodedTokens.RefreshToken))
            Pair(accessToken, refreshToken)

        } catch (e: TokenExpiredException) {
            try {
                val refreshToken = verifier.verify(JWT.decode(encodedTokens.RefreshToken))
                val id = refreshToken.getClaim("key").asString()
                val tokenCount = refreshToken.getClaim("count").asInt()
                val tokenPermissionLevel = refreshToken.getClaim("permissionLevel").asString()
                val userInfo = repo.getById(id)

                val accessToken = if (userInfo.count == tokenCount) {
                    verifier.verify(signAccessToken(id, tokenPermissionLevel))
                } else {
                    null
                }
                val newRefreshToken = JWT.decode(
                    signRefreshToken(
                        id,
                        count = tokenCount,
                        permissionLevel = tokenPermissionLevel
                    )
                )
                Pair(accessToken, newRefreshToken)
            } catch (e: JWTVerificationException) {
                Pair(null, null)
            }
            //Pair(null, null)
        } catch (t: Throwable) {
            Pair(null, null)
        }

        setAccessTokens(call, accessToken?.token.toString(), refreshToken?.token.toString())
        return buildLoggedInUser(accessToken)
    }

    private fun buildLoggedInUser(jwt: DecodedJWT?): LoggedInUser? {
        if (jwt != null) {
            return LoggedInUser(
                id = jwt.getClaim("key").asString(),
                permissionLevel = jwt.getClaim("permissionLevel").asString()
            )
        }
        return null
    }

    /**
     *
     * adds to the count of the specified user so that if the refresh token is inspected it will not match and will not
     * validate
     *
     * @param id user to invalidate
     */
    fun invalidateRefreshToken(id: String) {
        val user = repo.getById(id)
        val count = user.count
        val newCount = (count + 1) % Int.MAX_VALUE
        TODO()
    }

    /**
     * @param call The ApplicationCall that has access to cookies
     * @return returns the cookies as strings in an EncodedTokens data class
     * EncodedTokens class will have null values if no tokens present
     */
    private fun getAccessTokens(call: ApplicationCall): EncodedTokens {

        val refreshToken = call.request.headers["RefreshToken"] ?: "no-refresh-token"
        val accessToken = call.request.headers["AccessToken"] ?: "no-access-token"

        return EncodedTokens(
            AccessToken = accessToken,
            RefreshToken = refreshToken
        )
    }

    /**
     * @param call The ApplicationCall that has access to cookies
     * @param accessToken access token string that represents
     * the valid access token or null if user is no longer authenticated
     * @param refreshToken refresh token string that represents
     * the valid access token or null if user is no longer authenticated
     */
    private fun setAccessTokens(call: ApplicationCall, accessToken: String?, refreshToken: String?) {

        call.response.header("Access-Control-Expose-Headers", "AccessToken, RefreshToken")

        call.response.header("AccessToken", accessToken ?: "")
        call.response.header("RefreshToken", refreshToken ?: "")

    }

    /**
     * @param id the user Id associated with the access token intended to be generated
     * @param permissionLevel the permission level associated with that user
     * @return a valid and fresh access token
     */
    private fun signAccessToken(id: String, permissionLevel: String? = null): String {
        val date = GregorianCalendar.getInstance().apply {
            this.time = Date()
            this.add(Calendar.MINUTE, 5)
        }.time

        val actualPermissionLevel: String = permissionLevel ?: repo.getById(id).permissionLevel

        return JWT.create()
            .withIssuer(id)
            .withExpiresAt(date)
            .withClaim("key", id)
            .withClaim("permissionLevel", actualPermissionLevel)
            .sign(algorithm)
    }
    /**
     * @param id the user Id associated with the access token intended to be generated
     * @param permissionLevel the permission level associated with that user
     * @param count the count associated with that user
     *
     * @return a valid and fresh refresh token
     */
    private fun signRefreshToken(
        id: String,
        permissionLevel: String? = null,
        count: Int? = null
    ): String {
        val date = GregorianCalendar.getInstance().apply {
            this.time = Date()
            this.add(Calendar.MINUTE, 8440)
        }.time

        val actualPermissionLevel: String = permissionLevel ?: repo.getById(id).permissionLevel
        val actualCount = count ?: repo.getById(id).count

        return JWT.create()
            .withIssuer(id)
            .withExpiresAt(date)
            .withClaim("key", id)
            .withClaim("count", actualCount)
            .withClaim("permissionLevel", actualPermissionLevel)
            .sign(algorithm)
    }
}